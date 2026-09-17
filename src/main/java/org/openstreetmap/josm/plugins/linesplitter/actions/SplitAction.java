/*
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2026 JOSM Plugin Builder
 */
package org.openstreetmap.josm.plugins.linesplitter.actions;
import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.*;
import org.openstreetmap.josm.data.osm.*;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.gui.*;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.Shortcut;
import javax.swing.*;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.openstreetmap.josm.tools.I18n.tr;

public class SplitAction extends JosmAction {
    public SplitAction() {
        super(tr("Line Splitter"), "line-splitter", tr("Line Splitter"),
              Shortcut.registerShortcut("mytools:line_splitter", tr("Mytools: {0}", tr("Line Splitter")), 
              KeyEvent.VK_Z, Shortcut.CTRL_SHIFT), true);
        putValue(Action.ACCELERATOR_KEY, getShortcut().getKeyStroke());
    }
    @Override
    public void actionPerformed(java.awt.event.ActionEvent e) {
        DataSet ds = MainApplication.getLayerManager().getEditDataSet();
        if (ds == null) return;
        List<Way> ways = ds.getSelectedWays().stream().collect(Collectors.toList());
        if (ways.isEmpty()) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), tr("No ways selected to split."));
            return;
        }
        int lastMaxPoints = Config.getPref().getInt("linesplitter.maxpoints", 10);
        if (lastMaxPoints < 2) lastMaxPoints = 10;
        
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(lastMaxPoints, 2, Integer.MAX_VALUE, 1));
        setupAutoClamp(spinner, 2, Integer.MAX_VALUE, true);
        
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = new Insets(2, 2, 2, 5);
        panel.add(new JLabel(tr("Maximum points per segment:")), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0; gbc.insets = new Insets(2, 2, 2, 2);
        panel.add(spinner, gbc);
        
        int res = JOptionPane.showConfirmDialog(MainApplication.getMainFrame(), panel, tr("Split settings"), JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            try {
                spinner.commitEdit();
            } catch (Exception ignored) {}
            int maxPoints = (Integer) spinner.getValue();
            Config.getPref().putInt("linesplitter.maxpoints", maxPoints);
            runSplitting(ds, ways, maxPoints);
        }
    }
    
    private void runSplitting(DataSet ds, List<Way> ways, int maxPoints) {
        JDialog pd = new JDialog(MainApplication.getMainFrame(), tr("Splitting lines..."), false);
        JProgressBar bar = new JProgressBar(0, ways.size());
        bar.setPreferredSize(new Dimension(300, 20));
        pd.add(bar);
        pd.pack();
        pd.setLocationRelativeTo(MainApplication.getMainFrame());

        new SwingWorker<BulkSplitCommand, Integer>() {
            @Override protected BulkSplitCommand doInBackground() {
                Map<Way, List<Node>> wayOldNodes = new ConcurrentHashMap<>();
                Map<Way, List<Node>> wayNewNodes = new ConcurrentHashMap<>();
                Map<Way, List<Way>> newWays = new ConcurrentHashMap<>();
                AtomicInteger processed = new AtomicInteger(0);
                
                ways.parallelStream().forEach(way -> {
                    List<Node> nodes = way.getNodes();
                    if (nodes.size() <= 2 || nodes.size() <= maxPoints) {
                        publish(processed.incrementAndGet());
                        return;
                    }
                    
                    boolean isClosed = nodes.size() > 1 && nodes.get(0) == nodes.get(nodes.size() - 1);
                    List<Node> workingNodes = new ArrayList<>(nodes);
                    if (isClosed) {
                        workingNodes.remove(workingNodes.size() - 1);
                    }
                    
                    int totalPoints = workingNodes.size();
                    if (isClosed && totalPoints <= maxPoints) {
                        publish(processed.incrementAndGet());
                        return;
                    }
                    
                    List<List<Node>> segmentNodeLists = new ArrayList<>();
                    if (isClosed) {
                        int segmentsCount = (int) Math.ceil((double) totalPoints / (maxPoints - 1));
                        for (int i = 0; i < segmentsCount; i++) {
                            int startIdx = i * (maxPoints - 1);
                            int endIdx = Math.min(startIdx + maxPoints, totalPoints);
                            List<Node> segNodes = new ArrayList<>();
                            for (int j = startIdx; j < endIdx; j++) {
                                segNodes.add(workingNodes.get(j % totalPoints));
                            }
                            if (i == segmentsCount - 1) {
                                Node firstNode = workingNodes.get(0);
                                if (!segNodes.isEmpty() && segNodes.get(segNodes.size() - 1) != firstNode) {
                                    segNodes.add(firstNode);
                                }
                            }
                            if (segNodes.size() >= 2) {
                                segmentNodeLists.add(segNodes);
                            }
                        }
                    } else {
                        int segmentsCount = (int) Math.ceil((double) (totalPoints - 1) / (maxPoints - 1));
                        for (int i = 0; i < segmentsCount; i++) {
                            int startIdx = i * (maxPoints - 1);
                            int endIdx = Math.min(startIdx + maxPoints, totalPoints);
                            List<Node> segNodes = new ArrayList<>();
                            for (int j = startIdx; j < endIdx; j++) {
                                segNodes.add(workingNodes.get(j));
                            }
                            if (segNodes.size() >= 2) {
                                segmentNodeLists.add(segNodes);
                            }
                        }
                    }
                    
                    if (segmentNodeLists.size() > 1) {
                        List<Way> addedSegments = new ArrayList<>();
                        for (int i = 1; i < segmentNodeLists.size(); i++) {
                            Way newWay = new Way();
                            newWay.setNodes(segmentNodeLists.get(i));
                            newWay.setKeys(way.getKeys());
                            addedSegments.add(newWay);
                        }
                        wayOldNodes.put(way, new ArrayList<>(nodes));
                        wayNewNodes.put(way, segmentNodeLists.get(0));
                        newWays.put(way, addedSegments);
                    }
                    publish(processed.incrementAndGet());
                });
                
                Map<Relation, List<RelationMember>> relationNewMembers = new HashMap<>();
                for (Way original : wayNewNodes.keySet()) {
                    List<Way> segments = new ArrayList<>();
                    segments.add(original);
                    segments.addAll(newWays.getOrDefault(original, Collections.emptyList()));
                    
                    for (OsmPrimitive ref : original.getReferrers()) {
                        if (ref instanceof Relation) {
                            Relation rel = (Relation) ref;
                            List<RelationMember> currentMembers = relationNewMembers.computeIfAbsent(rel, r -> new ArrayList<>(r.getMembers()));
                            List<RelationMember> updatedMembers = new ArrayList<>();
                            for (RelationMember rm : currentMembers) {
                                if (rm.getMember() == original) {
                                    for (Way seg : segments) {
                                        updatedMembers.add(new RelationMember(rm.getRole(), seg));
                                    }
                                } else {
                                    updatedMembers.add(rm);
                                }
                            }
                            relationNewMembers.put(rel, updatedMembers);
                        }
                    }
                }
                
                return new BulkSplitCommand(ds, wayOldNodes, wayNewNodes, newWays, relationNewMembers);
            }
            
            @Override protected void process(List<Integer> chunks) { bar.setValue(chunks.get(chunks.size()-1)); }
            @Override protected void done() {
                pd.dispose();
                try {
                    BulkSplitCommand cmd = get();
                    if (cmd != null && cmd.hasChanges()) {
                        UndoRedoHandler.getInstance().add(cmd);
                        JOptionPane.showMessageDialog(MainApplication.getMainFrame(), 
                            tr("Split {0} ways into {1} segments.", cmd.getSplitCount(), cmd.getTotalSegments()));
                    }
                } catch (Exception ignored) {}
            }
        }.execute();
        pd.setVisible(true);
    }
    
    private static class BulkSplitCommand extends Command {
        private final Map<Way, List<Node>> wayOldNodes;
        private final Map<Way, List<Node>> wayNewNodes;
        private final Map<Way, List<Way>> newWays;
        private final Map<Relation, List<RelationMember>> relationOldMembers;
        private final Map<Relation, List<RelationMember>> relationNewMembers;
        private final int splitCount;
        private final int totalSegments;
        
        public BulkSplitCommand(DataSet ds, Map<Way, List<Node>> wayOldNodes, Map<Way, List<Node>> wayNewNodes,
                                Map<Way, List<Way>> newWays, Map<Relation, List<RelationMember>> relationNewMembers) {
            super(ds);
            this.wayOldNodes = wayOldNodes;
            this.wayNewNodes = wayNewNodes;
            this.newWays = newWays;
            this.relationNewMembers = relationNewMembers;
            this.relationOldMembers = new HashMap<>();
            this.splitCount = wayNewNodes.size();
            
            int segCount = wayNewNodes.size();
            for (List<Way> segs : newWays.values()) {
                segCount += segs.size();
            }
            this.totalSegments = segCount;
            
            for (Relation rel : relationNewMembers.keySet()) {
                relationOldMembers.put(rel, new ArrayList<>(rel.getMembers()));
            }
        }
        
        public boolean hasChanges() { return !wayNewNodes.isEmpty(); }
        public int getSplitCount() { return splitCount; }
        public int getTotalSegments() { return totalSegments; }
        
        @Override
        public boolean executeCommand() {
            DataSet ds = getAffectedDataSet();
            if (ds == null) return false;
            ds.beginUpdate();
            try {
                for (List<Way> segments : newWays.values()) {
                    for (Way segment : segments) {
                        if (segment.getDataSet() == null) {
                            ds.addPrimitive(segment);
                        } else {
                            segment.setDeleted(false);
                        }
                    }
                }
                
                for (Map.Entry<Way, List<Node>> entry : wayNewNodes.entrySet()) {
                    entry.getKey().setNodes(entry.getValue());
                }
                
                for (Map.Entry<Relation, List<RelationMember>> entry : relationNewMembers.entrySet()) {
                    entry.getKey().setMembers(entry.getValue());
                }
            } finally { ds.endUpdate(); }
            return true;
        }
        
        @Override
        public void undoCommand() {
            DataSet ds = getAffectedDataSet();
            if (ds == null) return;
            ds.beginUpdate();
            try {
                for (Map.Entry<Relation, List<RelationMember>> entry : relationOldMembers.entrySet()) {
                    entry.getKey().setMembers(entry.getValue());
                }
                
                for (Map.Entry<Way, List<Node>> entry : wayOldNodes.entrySet()) {
                    entry.getKey().setNodes(entry.getValue());
                }
                
                for (List<Way> segments : newWays.values()) {
                    for (Way segment : segments) {
                        if (segment.getDataSet() != null) {
                            segment.setDeleted(true);
                        }
                    }
                }
            } finally { ds.endUpdate(); }
        }
        
        @Override
        public void fillModifiedData(Collection<OsmPrimitive> m, Collection<OsmPrimitive> d, Collection<OsmPrimitive> a) {
            m.addAll(wayNewNodes.keySet());
            m.addAll(relationNewMembers.keySet());
            a.addAll(newWays.values().stream().flatMap(List::stream).collect(Collectors.toList()));
        }
        
        @Override
        public String getDescriptionText() { return tr("Line Splitter"); }
        
        @Override
        public Collection<OsmPrimitive> getParticipatingPrimitives() {
            Set<OsmPrimitive> all = new HashSet<>(wayNewNodes.keySet());
            for (List<Way> segs : newWays.values()) {
                all.addAll(segs);
            }
            all.addAll(relationNewMembers.keySet());
            return all;
        }
    }
    
    private void setupAutoClamp(JSpinner spinner, int min, int max, boolean isInteger) {
        JSpinner.DefaultEditor editor = (JSpinner.DefaultEditor) spinner.getEditor();
        JFormattedTextField field = editor.getTextField();
        javax.swing.Timer timer = new javax.swing.Timer(1000, e -> {
            try {
                String text = field.getText().trim();
                if (!text.isEmpty()) {
                    long lVal = Long.parseLong(text);
                    if (isInteger) {
                        int iVal = (int) Math.round(lVal);
                        if (iVal < min) {
                            spinner.setValue(min);
                        } else if (iVal > max) {
                            spinner.setValue(max);
                        } else {
                            spinner.setValue(iVal);
                        }
                    }
                }
            } catch (Exception ex) {
                try { spinner.commitEdit(); } catch (Exception ignored) {}
            }
        });
        timer.setRepeats(false);
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void trigger() { timer.restart(); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { trigger(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { trigger(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { trigger(); }
        });
    }
}