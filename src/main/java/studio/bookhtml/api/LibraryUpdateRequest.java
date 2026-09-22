package studio.bookhtml.api;

/** Exactly one field must be supplied; validation lives in BookService. */
public record LibraryUpdateRequest(String title, Boolean archived) {}
