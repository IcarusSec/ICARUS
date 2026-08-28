package icarus.core;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

public class I18n {

    private static final String BUNDLE_NAME = "icarus.ui.resources.messages";
    private static ResourceBundle bundle;
    private static Locale currentLocale;
    private static ModuleConfig configRef;

    public static void initialize(ModuleConfig config) {
        configRef = config;
        String lang = config.getString(ModuleConfig.UI_LANGUAGE_KEY, "pt-BR");
        setLocale(lang);
    }

    public static void setLocale(String languageTag) {
        if ("en".equalsIgnoreCase(languageTag) || "en-US".equalsIgnoreCase(languageTag)) {
            currentLocale = Locale.ENGLISH;
        } else {
            currentLocale = new Locale("pt", "BR");
        }
        
        try {
            bundle = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale);
        } catch (MissingResourceException e) {
            // Se falhar de primeira (ex: rodando sem compilar resources), tente o fallback
            try {
                bundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH);
            } catch (MissingResourceException ex2) {
                bundle = null;
            }
        }
    }

    public static String t(String key, Object... args) {
        if (bundle == null) {
            return "!" + key + "!";
        }
        try {
            String value = bundle.getString(key);
            if (args.length > 0) {
                try {
                    return MessageFormat.format(value, args);
                } catch (IllegalArgumentException ex) {
                    return value;
                }
            }
            return value;
        } catch (MissingResourceException e) {
            if (args.length > 0 && args[0] instanceof String) {
                return (String) args[0];
            }
            return "!" + key + "!";
        }
    }

    public static String getLanguage() {
        if (configRef != null) {
            return configRef.getString(ModuleConfig.UI_LANGUAGE_KEY, "pt-BR");
        }
        return "pt-BR";
    }
}
