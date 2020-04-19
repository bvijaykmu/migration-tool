package org.openl.repository.migrator.jcr.exception;

import org.openl.rules.common.CommonException;

public class PropertyException extends CommonException {
    private static final long serialVersionUID = -125302643337515712L;

    public PropertyException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public PropertyException(String pattern, Throwable cause, Object... params) {
        super(pattern, cause, params);
    }
}
