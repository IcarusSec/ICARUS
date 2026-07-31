package icarus.autoauth;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;

import javax.swing.*;
import java.awt.Component;
import java.awt.Font;

/**
 * Adds a read-only "AutoAuth" tab next to Raw/Pretty in every request editor, Repeater
 * included. Burp calls {@link ExtensionProvidedHttpRequestEditor#setRequestResponse} on this
 * automatically whenever the editor's underlying request changes — e.g. right after a
 * Repeater Send — which is the only way to keep a displayed request in sync with AutoAuth's
 * wire-level injection: {@code HttpHandler} modifications never propagate back into an
 * already-open editor pane on their own (Montoya has no such hook), but Burp's own tab-refresh
 * mechanism for registered editor providers does it for free.
 *
 * Deliberately backed by a plain {@link JTextArea} rather than Burp's own HttpRequestEditor/
 * RawEditor widgets: those carry Burp's native right-click menu, including ICARUS's own
 * AutoAuth context-menu actions (Set as Auth Token Source / Add Auth Token Destination / Sync
 * AutoAuth Token) — but those compute selection offsets against whatever text is on screen,
 * and this tab shows the *injected* body/headers, not the real editable request, so an offset
 * captured here would silently point at the wrong thing. A plain Swing component isn't a
 * Burp-recognized editor surface, so Burp never offers its context menu inside this tab at
 * all — none of AutoAuth's own actions are reachable from its own preview.
 */
public final class AutoAuthPreviewEditorProvider implements HttpRequestEditorProvider {

    private final AutoAuthModule autoAuth;

    public AutoAuthPreviewEditorProvider(AutoAuthModule autoAuth) {
        this.autoAuth = autoAuth;
    }

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext creationContext) {
        return new PreviewEditor();
    }

    private final class PreviewEditor implements ExtensionProvidedHttpRequestEditor {
        private final JTextArea textArea = new JTextArea();
        private final JScrollPane scrollPane = new JScrollPane(textArea);
        private HttpRequest currentRequest;

        PreviewEditor() {
            textArea.setEditable(false);
            textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        }

        @Override
        public void setRequestResponse(HttpRequestResponse requestResponse) {
            currentRequest = autoAuth.injectIfApplicable(requestResponse.request());
            textArea.setText(currentRequest.toString());
            textArea.setCaretPosition(0);
        }

        @Override
        public boolean isEnabledFor(HttpRequestResponse requestResponse) {
            HttpRequest request = requestResponse.request();
            return autoAuth.injectIfApplicable(request) != request; // only show when AutoAuth actually changes something
        }

        @Override
        public String caption() {
            return "AutoAuth";
        }

        @Override
        public Component uiComponent() {
            return scrollPane;
        }

        @Override
        public Selection selectedData() {
            return null; // no Burp Selection to hand back from a plain JTextArea
        }

        @Override
        public boolean isModified() {
            return false; // read-only preview, never diverges from what setRequestResponse last set
        }

        @Override
        public HttpRequest getRequest() {
            return currentRequest;
        }
    }
}
