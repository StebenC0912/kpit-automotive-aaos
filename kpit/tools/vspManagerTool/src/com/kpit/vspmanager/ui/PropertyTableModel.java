package com.kpit.vspmanager.ui;

import com.kpit.vspmanager.model.HvacProperty;
import com.kpit.vspmanager.model.PropertySnapshot;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One fixed row per (property, area) pair, in dump() order; cells fill in as snapshots arrive. */
public final class PropertyTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Property", "Area", "Value", "Real VHAL", "Updated"};
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final List<RowKey> rowOrder = new ArrayList<>();
    private final Map<String, PropertySnapshot> snapshotsByKey = new HashMap<>();

    public PropertyTableModel() {
        for (HvacProperty property : HvacProperty.values()) {
            for (int area : property.areas) {
                rowOrder.add(new RowKey(property, area));
            }
        }
    }

    public void applySnapshots(List<PropertySnapshot> snapshots) {
        for (PropertySnapshot snapshot : snapshots) {
            snapshotsByKey.put(snapshot.key(), snapshot);
        }
        // fireTableDataChanged() clears the JTable's selection on every call - JTable's
        // tableChanged() wipes selection whenever an event's lastRow == Integer.MAX_VALUE with
        // column == ALL_COLUMNS, which is exactly what fireTableDataChanged() sends. Firing a
        // bounded row-update instead repaints every row without matching that condition, so a
        // row selected while watching survives the next poll tick.
        if (!rowOrder.isEmpty()) {
            fireTableRowsUpdated(0, rowOrder.size() - 1);
        }
    }

    public RowKey rowKeyAt(int rowIndex) {
        return rowOrder.get(rowIndex);
    }

    @Override
    public int getRowCount() {
        return rowOrder.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        RowKey rowKey = rowOrder.get(rowIndex);
        PropertySnapshot snapshot = snapshotsByKey.get(rowKey.key());
        switch (columnIndex) {
            case 0:
                // PROP_TEMP_OUTSIDE drifts ~5s on its own (FakeHvacBackend) - flag it so a
                // manual Set snapping back shortly after doesn't read as a bug.
                String note = rowKey.property == HvacProperty.PROP_TEMP_OUTSIDE ? " (drifts ~5s)" : "";
                return rowKey.property.name() + note;
            case 1:
                return rowKey.area;
            case 2:
                return snapshot == null ? "" : snapshot.getValue();
            case 3:
                return snapshot == null ? "" : snapshot.getRealVhalLabel();
            case 4:
                return snapshot == null ? "" : TIME_FORMAT.format(snapshot.getTimestamp());
            default:
                return "";
        }
    }

    public static final class RowKey {
        public final HvacProperty property;
        public final int area;

        RowKey(HvacProperty property, int area) {
            this.property = property;
            this.area = area;
        }

        public String key() {
            return property.name() + "@" + area;
        }
    }
}
