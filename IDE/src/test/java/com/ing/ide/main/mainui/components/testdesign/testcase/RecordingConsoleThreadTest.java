package com.ing.ide.main.mainui.components.testdesign.testcase;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import com.ing.ide.main.utils.ConsolePanel;
import java.awt.Window;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import org.testng.annotations.Test;

public class RecordingConsoleThreadTest {

    @Test
    public void oldLockOrderDeadlocksButConsoleBoundaryRemainsResponsive() throws Exception {
        runChild("legacy");
        runChild("fixed");
    }

    private static void runChild(String mode) throws Exception {
        Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            RecordingConsoleThreadTest.class.getName(),
            mode
        )
            .redirectErrorStream(true)
            .start();
        try {
            assertTrue(child.waitFor(40, TimeUnit.SECONDS), "Reproduction did not finish: " + mode);
            String output = new String(child.getInputStream().readAllBytes());
            assertEquals(child.exitValue(), 0, output);
            assertTrue(output.contains("PASS " + mode), output);
            System.out.println(output);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void workerRecordingCallReadsAndMutatesToolbarOnEdt() throws Exception {
        TestCaseComponent component = mock(TestCaseComponent.class);
        TestCaseToolBar toolbar = mock(TestCaseToolBar.class);
        set(component, "toolBar", toolbar);
        set(component, "consoleDialog", mock(TestCaseComponent.ConsoleDialog.class));
        set(component, "launchPlaywrightTask", new CompletableFuture<Void>());
        when(toolbar.isRecording())
            .thenAnswer(
                call -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return false;
                }
            );
        doAnswer(
                call -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return null;
                }
            )
            .when(toolbar)
            .enableRecordButton();
        doCallRealMethod().when(component).record();
        component.record();
        SwingUtilities.invokeAndWait(() -> {});
        verify(toolbar).enableRecordButton();
    }

    @Test
    public void setupFailureReachesBackgroundCallerUnchanged() throws Exception {
        TestCaseComponent component = mock(TestCaseComponent.class);
        TestCaseToolBar toolbar = mock(TestCaseToolBar.class);
        set(component, "toolBar", toolbar);
        IllegalStateException failure = new IllegalStateException("recording setup failed");
        when(toolbar.isRecording()).thenThrow(failure);
        doCallRealMethod().when(component).record();
        try {
            component.record();
            fail("Setup failure was swallowed");
        } catch (IllegalStateException actual) {
            assertSame(actual, failure);
        }
    }

    @Test
    public void stoppingFromEdtDoesNotWaitForRecorderProcess() throws Exception {
        TestCaseComponent component = mock(TestCaseComponent.class);
        TestCaseToolBar toolbar = mock(TestCaseToolBar.class);
        Process process = mock(Process.class);
        set(component, "toolBar", toolbar);
        set(component, "activePlaywrightProcess", process);
        when(toolbar.isRecording()).thenReturn(true);
        when(process.isAlive()).thenReturn(true);
        when(process.descendants()).thenAnswer(call -> java.util.stream.Stream.empty());
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            waiting.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return true;
        });
        doAnswer(call -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            finished.countDown();
            return null;
        }).when(toolbar).enableRecordButton();
        doCallRealMethod().when(component).record();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    component.record();
                } catch (java.io.IOException ex) {
                    throw new AssertionError(ex);
                }
            });
            assertTrue(waiting.await(10, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS));
        verify(toolbar).setRecordingState(false);
        verify(process).destroy();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = TestCaseComponent.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void main(String[] args) throws Exception {
        boolean legacy = args[0].equals("legacy");
        AtomicReference<TestCaseComponent.ConsoleDialog> dialogRef = new AtomicReference<>();
        AtomicReference<JTextPane> textRef = new AtomicReference<>();
        AtomicReference<ConsolePanel> panelRef = new AtomicReference<>();
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch treeOwned = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(
            () -> {
                TestCaseComponent outer = mock(TestCaseComponent.class);
                TestCaseComponent.ConsoleDialog dialog = outer.new ConsoleDialog();
                ConsolePanel panel = (ConsolePanel) dialog.getContentPane().getComponent(0);
                JTextPane text = (JTextPane) ((JScrollPane) panel.getComponent(0)).getViewport()
                    .getView();
                text.setText("Previous recording output\n".repeat(50));
                dialogRef.set(dialog);
                textRef.set(text);
                panelRef.set(panel);
                ((AbstractDocument) text.getDocument()).setDocumentFilter(
                        new DocumentFilter() {

                            @Override
                            public void remove(FilterBypass fb, int offset, int length)
                                throws BadLocationException {
                                removing.countDown();
                                try {
                                    if (!treeOwned.await(10, TimeUnit.SECONDS)) {
                                        throw new AssertionError(
                                            "Layout worker never acquired tree lock"
                                        );
                                    }
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(ex);
                                }
                                super.remove(fb, offset, length);
                            }
                        }
                    );
            }
        );
        Thread worker = new Thread(
            () -> {
                try {
                    if (!removing.await(10, TimeUnit.SECONDS)) throw new AssertionError(
                        "No document writer"
                    );
                    synchronized (textRef.get().getTreeLock()) {
                        treeOwned.countDown();
                        if (legacy) {
                            // The pre-fix showConsole entered Window.pack on this worker.
                            dialogRef.get().pack();
                        } else {
                            dialogRef.get().showConsole();
                        }
                    }
                } catch (InterruptedException ex) {
                    throw new AssertionError(ex);
                }
            },
            "ing-aufnahme-start-reproduction"
        );
        worker.start();
        panelRef.get().clear();
        if (legacy) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean inverted = false;
            while (System.nanoTime() < deadline && !inverted) {
                var threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
                boolean edtBlocked = false;
                boolean workerReading = false;
                for (var thread : threads) {
                    if (
                        thread.getThreadName().startsWith("AWT-EventQueue") &&
                        thread.getLockOwnerId() == worker.getId()
                    ) {
                        edtBlocked = true;
                    }
                    if (thread.getThreadId() == worker.getId()) {
                        for (var frame : thread.getStackTrace()) {
                            if (
                                frame.getClassName().equals("javax.swing.text.AbstractDocument") &&
                                frame.getMethodName().equals("readLock")
                            ) workerReading = true;
                        }
                    }
                }
                inverted = edtBlocked && workerReading;
                Thread.yield();
            }
            if (!inverted) throw new AssertionError(
                "Old document/tree lock inversion not observed"
            );
            System.out.println(
                "PASS legacy: EDT waits worker AWTTreeLock; worker Window.pack waits AbstractDocument.readLock"
            );
            System.exit(0); // Only this deliberately deadlocked fixture JVM.
        }
        worker.join(10000);
        if (worker.isAlive()) throw new AssertionError("Console worker blocked");
        for (int i = 0; i < 30; i++) {
            panelRef.get().appendLine("Recording started " + i);
            panelRef.get().clear();
            dialogRef.get().showConsole();
            SwingUtilities.invokeAndWait(
                () -> {
                    assertTrue(dialogRef.get().isVisible());
                    assertEquals(textRef.get().getText(), "");
                    dialogRef.get().dispose();
                }
            );
        }
        SwingUtilities.invokeAndWait(
            () -> {
                for (Window window : Window.getWindows()) window.dispose();
            }
        );
        System.out.println(
            "PASS fixed: same interleaving completes; 30 console clear/open/close cycles and EDT heartbeats"
        );
        System.exit(0);
    }
}
