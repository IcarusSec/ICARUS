package com.icarus.ui.reportprofile.model;

import java.util.List;

public interface ProfileSpec {
    String name();
    boolean builtIn();
    List<SectionSpec> sections(); // in display order
    // Cover Page / Finding Card Layout selections, accent colors, font family/size,
    // severity badge colors — mirror your existing profile model's fields 1:1 here.
}
