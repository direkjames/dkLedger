package com.direk.dkledger.audit.model;

import java.util.List;

/** A table for the report, e.g. spawner payback times. */
public record Table(String title, List<String> headers, List<List<String>> rows) {
}
