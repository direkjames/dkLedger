package com.direk.dkledger.audit;

/** One config check. Checks only read the context and add findings, tables, faucets or sinks to it. */
public interface Check {

    /** "A1" to "A12". */
    String id();

    void run(AuditContext context);
}
