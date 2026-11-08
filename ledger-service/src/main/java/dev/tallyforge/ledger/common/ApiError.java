package dev.tallyforge.ledger.common;

/** JSON error body returned by every endpoint. */
public record ApiError(String code, String message) {}
