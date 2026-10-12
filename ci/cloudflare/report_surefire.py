#!/usr/bin/env python3
"""Emit named test counts from Maven Surefire XML reports."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = sorted(Path(".").glob("**/surefire-reports/TEST-*.xml"))
if not reports:
    print("CI_ERROR no Surefire XML reports found", file=sys.stderr)
    raise SystemExit(1)

totals = {"tests": 0, "passed": 0, "failures": 0, "errors": 0, "skipped": 0}
all_reports_have_passes = True
for report in reports:
    try:
        root = ET.parse(report).getroot()
    except (ET.ParseError, OSError) as error:
        print(f"CI_ERROR invalid Surefire report path={report}: {error}", file=sys.stderr)
        raise SystemExit(1)
    if root.tag != "testsuite":
        print(f"CI_ERROR unexpected Surefire report root path={report} tag={root.tag}", file=sys.stderr)
        raise SystemExit(1)
    counts = {
        key: int(root.attrib.get(key, "0"))
        for key in ("tests", "failures", "errors", "skipped")
    }
    counts["passed"] = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
    if counts["passed"] <= 0:
        all_reports_have_passes = False
    for key, value in counts.items():
        totals[key] += value
    print(
        "TEST_REPORT "
        f"name={report.as_posix()} "
        f"tests={counts['tests']} passed={counts['passed']} "
        f"failures={counts['failures']} errors={counts['errors']} "
        f"skipped={counts['skipped']}"
    )

print(
    "TEST_TOTAL "
    f"reports={len(reports)} tests={totals['tests']} passed={totals['passed']} "
    f"failures={totals['failures']} errors={totals['errors']} "
    f"skipped={totals['skipped']}"
)
if (
    totals["tests"] <= 0
    or totals["passed"] <= 0
    or totals["failures"]
    or totals["errors"]
    or not all_reports_have_passes
):
    raise SystemExit(1)
