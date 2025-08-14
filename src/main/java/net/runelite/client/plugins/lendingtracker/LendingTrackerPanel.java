package net.runelite.client.plugins.lendingtracker;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.inject.Inject;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JList;
import net.runelite.client.ui.PluginPanel;

class LendingTrackerPanel extends PluginPanel
{
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);

    // Quick controls visible in panel (simple and non-intrusive)
    final JButton lendBtn = new JButton("Lend");
    final JButton borrowBtn = new JButton("Borrow");
    final JButton noneBtn = new JButton("Not a loan");
    final JTextField noteField = new JTextField();

    @Inject
    LendingTrackerPanel()
    {
        setLayout(new BorderLayout());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        noteField.setColumns(16);
        noteField.setToolTipText("Optional note for the next trade (e.g., 'agreed return tonight')");
        top.add(lendBtn);
        top.add(borrowBtn);
        top.add(noneBtn);
        top.add(noteField);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
    }

    void addLine(String line)
    {
        model.add(0, line);
        if (model.size() > 200)
        {
            model.removeElementAt(model.size() - 1);
        }
    }

    String consumeNote()
    {
        String t = noteField.getText();
        noteField.setText("");
        return t == null ? "" : t.trim();
    }
}
