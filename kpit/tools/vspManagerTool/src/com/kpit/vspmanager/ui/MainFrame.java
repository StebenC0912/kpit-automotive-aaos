package com.kpit.vspmanager.ui;

import com.kpit.vspmanager.adb.AdbClient;
import com.kpit.vspmanager.adb.AdbException;
import com.kpit.vspmanager.model.DumpResult;
import com.kpit.vspmanager.model.HvacProperty;
import com.kpit.vspmanager.model.PropertySnapshot;
import com.kpit.vspmanager.model.SetResult;
import com.kpit.vspmanager.poll.PollListener;
import com.kpit.vspmanager.poll.PropertyPoller;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class MainFrame extends JFrame implements PollListener {

    private static final long POLL_INTERVAL_MILLIS = 400;
    private static final float VEHICLE_STATE_UNLOCK_VALUE = 5.0f;

    private final JTextField adbPathField = new JTextField("adb", 10);
    private final JComboBox<String> deviceCombo = new JComboBox<>();
    private final JButton refreshDevicesButton = new JButton("Refresh Devices");
    private final JToggleButton watchToggle = new JToggleButton("Start Watching");
    private final JButton unlockButton = new JButton("Unlock Vehicle State (5.0)");
    private final JLabel statusLabel = new JLabel("Not connected");

    private final PropertyTableModel tableModel = new PropertyTableModel();
    private final JTable table = new JTable(tableModel);
    private final HistoryLogPane historyLogPane = new HistoryLogPane();

    private final JLabel selectedRowLabel = new JLabel("No row selected");
    private final JTextField setValueField = new JTextField(8);
    private final JButton setButton = new JButton("Set");

    private AdbClient adbClient;
    private PropertyPoller poller;

    public MainFrame() {
        super("vspManagerTool - HVAC");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);

        wireActions();

        setSize(900, 650);
        setLocationRelativeTo(null);
    }

    private JComponent buildToolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        panel.add(new JLabel("adb path:"));
        panel.add(adbPathField);
        panel.add(new JLabel("Device:"));
        panel.add(deviceCombo);
        panel.add(refreshDevicesButton);
        panel.add(watchToggle);
        panel.add(unlockButton);
        panel.add(statusLabel);
        return panel;
    }

    private JComponent buildCenter() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onRowSelected();
            }
        });

        JPanel setPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        setPanel.add(new JLabel("Selected:"));
        setPanel.add(selectedRowLabel);
        setPanel.add(new JLabel("New value:"));
        setPanel.add(setValueField);
        setPanel.add(setButton);

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);
        tablePanel.add(setPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, historyLogPane);
        split.setResizeWeight(0.65);
        return split;
    }

    private void wireActions() {
        refreshDevicesButton.addActionListener(e -> refreshDevices());
        watchToggle.addActionListener(e -> {
            if (watchToggle.isSelected()) {
                startWatching();
            } else {
                stopWatching();
            }
        });
        unlockButton.addActionListener(
                e -> sendSet(HvacProperty.PROP_VEHICLE_STATE, 0, VEHICLE_STATE_UNLOCK_VALUE));
        setButton.addActionListener(e -> onSetButtonPressed());
    }

    private void refreshDevices() {
        AdbClient client = new AdbClient(adbPathField.getText());
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return client.listDevices();
            }

            @Override
            protected void done() {
                try {
                    List<String> devices = get();
                    deviceCombo.removeAllItems();
                    for (String device : devices) {
                        deviceCombo.addItem(device);
                    }
                    historyLogPane.append("Found " + devices.size() + " device(s)");
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    historyLogPane.append("ERROR listing devices: " + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    private void startWatching() {
        adbClient = new AdbClient(adbPathField.getText());
        poller = new PropertyPoller(adbClient, this, POLL_INTERVAL_MILLIS);
        String selectedDevice = (String) deviceCombo.getSelectedItem();
        poller.setSerial(selectedDevice);
        poller.start();
        watchToggle.setText("Stop Watching");
        statusLabel.setText("Watching...");
        historyLogPane.append("Started watching " + (selectedDevice == null ? "(default device)" : selectedDevice));
    }

    private void stopWatching() {
        if (poller != null) {
            poller.stop();
        }
        watchToggle.setText("Start Watching");
        statusLabel.setText("Stopped");
        historyLogPane.append("Stopped watching");
    }

    private void onRowSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            selectedRowLabel.setText("No row selected");
            return;
        }
        PropertyTableModel.RowKey rowKey = tableModel.rowKeyAt(row);
        selectedRowLabel.setText(rowKey.property.name() + " area=" + rowKey.area);
        Object currentValue = tableModel.getValueAt(row, 2);
        setValueField.setText(currentValue == null ? "" : currentValue.toString());
    }

    private void onSetButtonPressed() {
        int row = table.getSelectedRow();
        if (row < 0) {
            historyLogPane.append("ERROR: select a row before Set");
            return;
        }
        PropertyTableModel.RowKey rowKey = tableModel.rowKeyAt(row);
        float value;
        try {
            value = Float.parseFloat(setValueField.getText().trim());
        } catch (NumberFormatException e) {
            historyLogPane.append("ERROR: '" + setValueField.getText() + "' is not a valid number");
            return;
        }
        sendSet(rowKey.property, rowKey.area, value);
    }

    private void sendSet(HvacProperty property, int area, float value) {
        AdbClient client = adbClient != null ? adbClient : new AdbClient(adbPathField.getText());
        String serial = (String) deviceCombo.getSelectedItem();
        historyLogPane.append("Sending SET " + property.name() + " area=" + area + " value=" + value);
        new SwingWorker<SetResult, Void>() {
            @Override
            protected SetResult doInBackground() throws AdbException {
                String raw = client.setProperty(serial, property.name(), area, value);
                return SetResult.parse(raw);
            }

            @Override
            protected void done() {
                try {
                    SetResult result = get();
                    historyLogPane.append((result.isSuccess() ? "OK: " : "ERROR: ") + result.getMessage());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    historyLogPane.append("ERROR sending set: " + ex.getCause().getMessage());
                }
            }
        }.execute();
    }

    @Override
    public void onUpdate(DumpResult result, List<PropertySnapshot> changed) {
        switch (result.getStatus()) {
            case OK:
                tableModel.applySnapshots(result.getSnapshots());
                statusLabel.setText("Connected - last update OK");
                for (PropertySnapshot snapshot : changed) {
                    historyLogPane.append(snapshot.getProperty().name() + " area=" + snapshot.getArea()
                            + " -> " + snapshot.getValue());
                }
                break;
            case NOT_READY:
                statusLabel.setText("vps-service / native VHAL bridge not ready");
                break;
            default:
                break;
        }
    }

    @Override
    public void onError(String message) {
        statusLabel.setText("Error - see log");
        historyLogPane.append("ERROR: " + message);
    }
}
