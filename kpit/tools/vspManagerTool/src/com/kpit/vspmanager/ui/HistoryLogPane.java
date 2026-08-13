package com.kpit.vspmanager.ui;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Font;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Append-only, timestamped log of property changes and Set actions for the running session. */
public final class HistoryLogPane extends JPanel {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final int MAX_LINES = 2000;

    private final JTextArea textArea = new JTextArea();

    public HistoryLogPane() {
        super(new BorderLayout());
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(textArea), BorderLayout.CENTER);
    }

    public void append(String message) {
        textArea.append("[" + TIME_FORMAT.format(Instant.now()) + "] " + message + "\n");
        trimIfNeeded();
        textArea.setCaretPosition(textArea.getDocument().getLength());
    }

    private void trimIfNeeded() {
        int excess = textArea.getLineCount() - MAX_LINES;
        if (excess > 0) {
            try {
                int endOffset = textArea.getLineEndOffset(excess - 1);
                textArea.replaceRange("", 0, endOffset);
            } catch (BadLocationException ignored) {
                // Best-effort trim; skip if offsets went stale from a concurrent append.
            }
        }
    }
}
