package icarus.report;

import icarus.report.model.ReportProfile;
import icarus.report.render.ReportData;
import icarus.report.render.ReportRenderContext;
import java.nio.file.Path;
import java.util.List;

/**
 * Service managing built-in and user-defined Report Profiles.
 */
public interface ReportProfileManager {

    /**
     * Lists all available report profiles (built-in and user-created).
     */
    List<ReportProfile> list();

    /**
     * Retrieves a profile by its ID.
     */
    ReportProfile get(String id);

    /**
     * Retrieves the currently active report profile.
     */
    ReportProfile active();

    /**
     * Sets the active report profile ID.
     */
    void setActive(String id);

    /**
     * Clones an existing profile to a new user-editable profile.
     */
    ReportProfile clone(String sourceId, String newName);

    /**
     * Saves or updates a user-defined profile (rejects built-in profiles).
     */
    void saveUserProfile(ReportProfile profile);

    /**
     * Deletes a user profile (rejects built-in profiles).
     */
    void deleteUserProfile(String id);

    /**
     * Exports a profile to JSON string.
     */
    String exportJson(String id);

    /**
     * Imports a profile from a JSON string, creating a user profile.
     */
    ReportProfile importJson(String json);

    /**
     * Binds domain report data with the currently active profile into an immutable render context.
     */
    ReportRenderContext bind(ReportData data, Path workingDir);

    /**
     * Binds domain report data with a specified profile into an immutable render context.
     */
    ReportRenderContext bind(String profileId, ReportData data, Path workingDir);
}
