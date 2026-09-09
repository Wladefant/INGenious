package com.ing.engine.execution.exception;

public class ActionException extends RuntimeException {
    public String ErrorName;
    public String ErrorDescription;

    public ActionException(String message) {
        super(message);
        this.ErrorDescription = message;
    }

    public ActionException(String message, Throwable cause) {
        super(message, cause);
        this.ErrorDescription = message;
    }

    public ActionException(Throwable ex) {
        super(ex);
        this.ErrorDescription = ex != null ? ex.getMessage() : null;
    }
}
