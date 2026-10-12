package com.direk.dkledger.audit;

/** How urgent a finding is. Sorted from most to least urgent. */
public enum Severity {
    /** Players can make unlimited money, or something is clearly broken. Fix before launch. */
    CRITICAL,
    /** Likely to cause inflation or confusion. Look at it soon. */
    WARNING,
    /** Good to know: a number, a summary or a change. */
    INFO
}
