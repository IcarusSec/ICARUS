package com.icarus.ui.reportprofile.model;

public interface SectionSpec {
    String id();            // stable key, e.g. "EXECUTIVE_SUMMARY"
    boolean enabled();
    boolean required();
    String title();         // editable display title, defaults to formatted id()
    String bodyTemplate();  // the {{token}}-containing text
}
