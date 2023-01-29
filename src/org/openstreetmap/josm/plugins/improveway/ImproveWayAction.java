// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.improveway;

import org.openstreetmap.josm.actions.ExpertToggleAction;
import org.openstreetmap.josm.actions.ExpertToggleAction.ExpertModeChangeListener;
import org.openstreetmap.josm.actions.mapmode.ImproveWayAccuracyAction;
import org.openstreetmap.josm.command.*;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.IWaySegment;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.NamedColorProperty;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.gui.util.KeyPressReleaseListener;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.PreferenceChangeEvent;
import org.openstreetmap.josm.spi.preferences.PreferenceChangedListener;
import org.openstreetmap.josm.tools.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.util.List;
import java.util.Timer;
import java.util.*;

import static org.openstreetmap.josm.tools.I18n.*;

/**
 * @author Alexander Kachkaev &lt;alexander@kachkaev.ru&gt;, 2011
 */
public class ImproveWayAction
    extends ImproveWayAccuracyAction
    implements KeyPressReleaseListener, ExpertModeChangeListener, PreferenceChangedListener
{
    protected Color turnColor;
    protected Color distanceColor;
    protected Color arcFillColor;
    protected Color arcStrokeColor;
    protected Color perpendicularLineColor;
    protected Color equalAngleCircleColor;

    protected transient Stroke arcStroke;
    protected transient Stroke perpendicularLineStroke;
    protected transient Stroke equalAngleCircleStroke;
    protected int dotSize;

    protected int arcRadiusPixels;
    protected int perpendicularLengthPixels;
    protected int turnTextDistance;
    protected int distanceTextDistance;
    protected int equalAngleCircleRadius;
    protected long longKeypressTime;

    protected boolean helpersEnabled = false;
    protected boolean helpersUseOriginal = false;
    protected final transient Shortcut helpersShortcut;
    protected long keypressTime = 0;
    protected boolean helpersEnabledBeforeKeypressed = false;
    protected Timer longKeypressTimer;
    protected boolean isExpert = false;

    protected boolean mod4 = false; // Windows/Super/Meta key

    /**
     * Constructs a new {@code ImproveWayAccuracyAction}.
     */
    public ImproveWayAction() {
        super(tr("Improve Way"), "improveway",
            tr("Improve Way mode"),
            Shortcut.registerShortcut("mapmode:ImproveWay",
                tr("Mode: {0}", tr("Improve Way")),
                KeyEvent.VK_W, Shortcut.DIRECT), Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

        helpersShortcut = Shortcut.registerShortcut("mapmode:enablewayaccuracyhelpers",
                tr("Mode: Enable way accuracy helpers"), KeyEvent.CHAR_UNDEFINED, Shortcut.NONE);

        ExpertToggleAction.addExpertModeChangeListener(this, true);
    }

    // -------------------------------------------------------------------------
    // Mode methods
    // -------------------------------------------------------------------------
    @Override
    public void enterMode() {
        super.enterMode();
        MainApplication.getMap().keyDetector.addKeyListener(this);
        if (!isExpert) return;
        helpersEnabled = false;
        keypressTime = 0;
        resetTimer();
        longKeypressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                helpersEnabled = true;
                helpersUseOriginal = true;
                MainApplication.getLayerManager().invalidateEditLayer();
            }
        }, longKeypressTime);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        MainApplication.getMap().keyDetector.removeKeyListener(this);
    }

    @Override
    protected void readPreferences() {
        super.readPreferences();
        turnColor = new NamedColorProperty(marktr("improve way accuracy helper turn angle text"), new Color(240, 240, 240, 200)).get();
        distanceColor = new NamedColorProperty(marktr("improve way accuracy helper distance text"), new Color(240, 240, 240, 120)).get();
        arcFillColor = new NamedColorProperty(marktr("improve way accuracy helper arc fill"), new Color(200, 200, 200, 50)).get();
        arcStrokeColor = new NamedColorProperty(marktr("improve way accuracy helper arc stroke"), new Color(240, 240, 240, 150)).get();
        perpendicularLineColor = new NamedColorProperty(marktr("improve way accuracy helper perpendicular line"), 
                new Color(240, 240, 240, 150)).get();
        equalAngleCircleColor = new NamedColorProperty(marktr("improve way accuracy helper equal angle circle"), 
                new Color(240, 240, 240, 150)).get();

        arcStroke = GuiHelper.getCustomizedStroke(Config.getPref().get("improvewayaccuracy.stroke.helper-arc", "1"));
        perpendicularLineStroke = GuiHelper.getCustomizedStroke(Config.getPref().get("improvewayaccuracy.stroke.helper-perpendicular-line", "1 6"));
        equalAngleCircleStroke = GuiHelper.getCustomizedStroke(Config.getPref().get("improvewayaccuracy.stroke.helper-eual-angle-circle", "1"));

        arcRadiusPixels = Config.getPref().getInt("improvewayaccuracy.helper-arc-radius", 200);
        perpendicularLengthPixels = Config.getPref().getInt("improvewayaccuracy.helper-perpendicular-line-length", 100);
        turnTextDistance = Config.getPref().getInt("improvewayaccuracy.helper-turn-text-distance", 15);
        distanceTextDistance = Config.getPref().getInt("improvewayaccuracy.helper-distance-text-distance", 15);
        equalAngleCircleRadius = Config.getPref().getInt("improvewayaccuracy.helper-equal-angle-circle-radius", 15);
        longKeypressTime = Config.getPref().getInt("improvewayaccuracy.long-keypress-time", 250);
    }

    @Override
    public void paint(Graphics2D g, MapView mv, Bounds bbox) {
        super.paint(g, mv, bbox);

        if (state == State.IMPROVING) {
            // Painting helpers visualizing turn angles and more
            if (!helpersEnabled) return;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawHalfDistanceLine(g, mv);
            drawTurnAnglePie(g, mv);
            drawEqualAnglePoint(g, mv);
        }
    }

    /**
     * Draw a perpendicular line at half distance between two endpoints
     */
    protected void drawHalfDistanceLine(Graphics2D g, MapView mv) {
        if (!(alt && !ctrl) && endpoint1 != null && endpoint2 != null) {
            Point p1 = mv.getPoint(endpoint1);
            Point p2 = mv.getPoint(endpoint2);
            Point half = new Point(
                (p1.x + p2.x)/2,
                (p1.y + p2.y)/2
            );
            double heading = Math.atan2(
                p2.y-p1.y,
                p2.x-p1.x
            ) + Math.PI/2;
            g.setStroke(perpendicularLineStroke);
            g.setColor(perpendicularLineColor);
            g.draw(new Line2D.Double(
                half.x + perpendicularLengthPixels * Math.cos(heading),
                half.y + perpendicularLengthPixels * Math.sin(heading),
                half.x - perpendicularLengthPixels * Math.cos(heading),
                half.y - perpendicularLengthPixels * Math.sin(heading)
            ));
        }
    }

    /**
     * Draw a pie (part of a circle) representing turn angle at each node
     */
    protected void drawTurnAnglePie(Graphics2D g, MapView mv) {
        Node node;
        LatLon coor, lastcoor = null;
        Point point, lastpoint = null;
        double distance;
        double heading, lastheading = 0;
        double turn;
        Arc2D arc;
        double arcRadius;
        boolean candidateSegmentVisited = false;
        int nodeCounter = 0;
        int nodesCount = targetWay.getNodesCount();
        int endLoop = nodesCount;
        if (targetWay.isClosed()) endLoop++;

        EastNorth newPointEN = getNewPointEN();
        Point newPoint = mv.getPoint(newPointEN);

        for (int i = 0; i < endLoop; i++) {
            // when way is closed we visit second node again
            // to get turn for start/end node
            node = targetWay.getNode(i == nodesCount ? 1 : i);
            if (!helpersUseOriginal && newPointEN != null &&
                ctrl &&
                !candidateSegmentVisited &&
                candidateSegment != null &&
                candidateSegment.getSecondNode() == node
            ) {
                coor = ProjectionRegistry.getProjection().eastNorth2latlon(newPointEN);
                point = newPoint;
                candidateSegmentVisited = true;
                i--;
            } else if (!helpersUseOriginal && newPointEN != null && !alt && !ctrl && node == candidateNode) {
                coor = ProjectionRegistry.getProjection().eastNorth2latlon(newPointEN);
                point = newPoint;
            } else if (!helpersUseOriginal && alt && !ctrl && node == candidateNode) {
                continue;
            } else {
                coor = node.getCoor();
                point = mv.getPoint(coor);
            }
            if (nodeCounter >= 1) {
                heading = ImproveWayHelper.fixHeading(-90+lastcoor.bearing(coor)*180/Math.PI);
                distance = lastcoor.greatCircleDistance(coor);
                if (nodeCounter >= 2) {
                    turn = Math.abs(ImproveWayHelper.fixHeading(heading-lastheading));
                    double fixedHeading = ImproveWayHelper.fixHeading(heading - lastheading);
                    g.setColor(turnColor);
                    ImproveWayHelper.drawDisplacedlabel(
                        lastpoint.x,
                        lastpoint.y,
                        turnTextDistance,
                        (lastheading + fixedHeading/2 + (fixedHeading >= 0 ? 90 : -90))*Math.PI/180,
                        String.format("%1.0f °", turn),
                        g
                    );
                    arcRadius = arcRadiusPixels;
                    arc = new Arc2D.Double(
                        lastpoint.x-arcRadius,
                        lastpoint.y-arcRadius,
                        arcRadius*2,
                        arcRadius*2,
                        -heading + (fixedHeading >= 0 ? 90 : -90),
                        fixedHeading,
                        Arc2D.PIE
                    );
                    g.setStroke(arcStroke);
                    g.setColor(arcFillColor);
                    g.fill(arc);
                    g.setColor(arcStrokeColor);
                    g.draw(arc);
                }

                // Display segment length
                // avoid doubling first segment on closed ways
                if (i != nodesCount) {
                    g.setColor(distanceColor);
                    ImproveWayHelper.drawDisplacedlabel(
                        (lastpoint.x+point.x)/2,
                        (lastpoint.y+point.y)/2,
                        distanceTextDistance,
                        (heading + 90)*Math.PI/180,
                        String.format("%1.0f m", distance),
                        g
                    );
                }

                lastheading = heading;
            }
            lastcoor = coor;
            lastpoint = point;
            nodeCounter++;
        }
    }

    /**
     * Draw a point where turn angle will be same with two neighbours
     */
    protected void drawEqualAnglePoint(Graphics2D g, MapView mv) {
        EastNorth equalAngleEN = findEqualAngleEN();
        if (equalAngleEN != null) {
            Point equalAnglePoint = mv.getPoint(equalAngleEN);
            Ellipse2D.Double equalAngleCircle = new Ellipse2D.Double(
                equalAnglePoint.x-equalAngleCircleRadius/2d,
                equalAnglePoint.y-equalAngleCircleRadius/2d,
                equalAngleCircleRadius,
                equalAngleCircleRadius);
            g.setStroke(equalAngleCircleStroke);
            g.setColor(equalAngleCircleColor);
            g.draw(equalAngleCircle);
        }
    }

    public EastNorth getNewPointEN() {
        if (mod4) {
            return findEqualAngleEN();
        } else if (mousePos != null) {
            return mv.getEastNorth(mousePos.x, mousePos.y);
        } else {
            return null;
        }
    }

    public EastNorth findEqualAngleEN() {
        int index1 = -1;
        int index2 = -1;
        int realNodesCount = targetWay.getRealNodesCount();

        for (int i = 0; i < realNodesCount; i++) {
            Node node = targetWay.getNode(i);
            if (node == candidateNode) {
                index1 = i-1;
                index2 = i+1;
            }
            if (candidateSegment != null) {
                if (node == candidateSegment.getFirstNode()) index1 = i;
                if (node == candidateSegment.getSecondNode()) index2 = i;
            }
        }

        int i11 = ImproveWayHelper.fixIndex(realNodesCount, targetWay.isClosed(), index1-1);
        int i12 = ImproveWayHelper.fixIndex(realNodesCount, targetWay.isClosed(), index1);
        int i21 = ImproveWayHelper.fixIndex(realNodesCount, targetWay.isClosed(), index2);
        int i22 = ImproveWayHelper.fixIndex(realNodesCount, targetWay.isClosed(), index2+1);
        if (i11 < 0 || i12 < 0 || i21 < 0 || i22 < 0) return null;

        EastNorth p11 = targetWay.getNode(i11).getEastNorth();
        EastNorth p12 = targetWay.getNode(i12).getEastNorth();
        EastNorth p21 = targetWay.getNode(i21).getEastNorth();
        EastNorth p22 = targetWay.getNode(i22).getEastNorth();

        double a1 = Geometry.getSegmentAngle(p11, p12);
        double a2 = Geometry.getSegmentAngle(p21, p22);
        double a = ImproveWayHelper.fixHeading((a2-a1)*180/Math.PI)*Math.PI/180/3;

        EastNorth p1r = p11.rotate(p12, -a);
        EastNorth p2r = p22.rotate(p21, a);

        return Geometry.getLineLineIntersection(p1r, p12, p21, p2r);
    }

    protected void drawIntersectingWayHelperLines(MapView mv, GeneralPath b, Point newPoint) {
        for (final OsmPrimitive referrer : candidateNode.getReferrers()) {
            if (!(referrer instanceof Way) || targetWay.equals(referrer)) {
                continue;
            }
            final List<Node> nodes = ((Way) referrer).getNodes();
            for (int i = 0; i < nodes.size(); i++) {
                if (!candidateNode.equals(nodes.get(i))) {
                    continue;
                }
                if (i > 0) {
                    final Point p = mv.getPoint(nodes.get(i - 1));
                    b.moveTo(newPoint.x, newPoint.y);
                    b.lineTo(p.x, p.y);
                }
                if (i < nodes.size() - 1) {
                    final Point p = mv.getPoint(nodes.get(i + 1));
                    b.moveTo(newPoint.x, newPoint.y);
                    b.lineTo(p.x, p.y);
                }
            }
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        dragging = false;
        if (!isEnabled() || e.getButton() != MouseEvent.BUTTON1) {
            return;
        }

        updateKeyModifiers(e);
        mousePos = e.getPoint();
        EastNorth newPointEN = getNewPointEN();

        if (state == State.SELECTING) {
            if (targetWay != null) {
                getLayerManager().getEditDataSet().setSelected(targetWay.getPrimitiveId());
                updateStateByCurrentSelection();
            }
        } else if (state == State.IMPROVING && newPointEN != null) {
            // Checking if the new coordinate is outside of the world
            if (new Node(newPointEN).isOutSideWorld()) {
                JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                        tr("Cannot add a node outside of the world."),
                        tr("Warning"), JOptionPane.WARNING_MESSAGE);
                return;
            }

            if (ctrl && !alt && candidateSegment != null) {
                // Adding a new node to the highlighted segment
                // Important: If there are other ways containing the same
                // segment, a node must added to all of that ways.
                Collection<Command> virtualCmds = new LinkedList<>();

                // Creating a new node
                Node virtualNode = new Node(
                    ProjectionRegistry.getProjection().eastNorth2latlon(newPointEN)
                );
                virtualCmds.add(new AddCommand(getLayerManager().getEditDataSet(), virtualNode));

                // Looking for candidateSegment copies in ways that are
                // referenced
                // by candidateSegment nodes
                List<Way> firstNodeWays = new ArrayList<>(Utils.filteredCollection(
                        candidateSegment.getFirstNode().getReferrers(),
                        Way.class));
                List<Way> secondNodeWays = new ArrayList<>(Utils.filteredCollection(
                        candidateSegment.getFirstNode().getReferrers(),
                        Way.class));

                Collection<IWaySegment<?, Way>> virtualSegments = new LinkedList<>();
                for (Way w : firstNodeWays) {
                    List<Pair<Node, Node>> wpps = w.getNodePairs(true);
                    for (Way w2 : secondNodeWays) {
                        if (!w.equals(w2)) {
                            continue;
                        }
                        // A way is referenced in both nodes.
                        // Checking if there is such segment
                        int i = -1;
                        for (Pair<Node, Node> wpp : wpps) {
                            ++i;
                            boolean ab = wpp.a.equals(candidateSegment.getFirstNode())
                                    && wpp.b.equals(candidateSegment.getSecondNode());
                            boolean ba = wpp.b.equals(candidateSegment.getFirstNode())
                                    && wpp.a.equals(candidateSegment.getSecondNode());
                            if (ab || ba) {
                                virtualSegments.add(new IWaySegment<>(w, i));
                            }
                        }
                    }
                }

                // Adding the node to all segments found
                for (IWaySegment<?, Way> virtualSegment : virtualSegments) {
                    Way w = virtualSegment.getWay();
                    Way wnew = new Way(w);
                    wnew.addNode(virtualSegment.getUpperIndex(), virtualNode);
                    virtualCmds.add(new ChangeCommand(w, wnew));
                }

                // Finishing the sequence command
                String text = trn("Add a new node to way",
                        "Add a new node to {0} ways",
                        virtualSegments.size(), virtualSegments.size());

                UndoRedoHandler.getInstance().add(new SequenceCommand(text, virtualCmds));

            } else if (alt && !ctrl && candidateNode != null) {
                // Deleting the highlighted node

                //check to see if node is in use by more than one object
                List<OsmPrimitive> referrers = candidateNode.getReferrers();
                Collection<Way> ways = Utils.filteredCollection(referrers, Way.class);
                if (referrers.size() != 1 || ways.size() != 1) {
                    // detach node from way
                    final Way newWay = new Way(targetWay);
                    final List<Node> nodes = newWay.getNodes();
                    nodes.remove(candidateNode);
                    newWay.setNodes(nodes);
                    UndoRedoHandler.getInstance().add(new ChangeCommand(targetWay, newWay));
                } else if (candidateNode.isTagged()) {
                    JOptionPane.showMessageDialog(MainApplication.getMainFrame(),
                            tr("Cannot delete node that has tags"),
                            tr("Error"), JOptionPane.ERROR_MESSAGE);
                } else {
                    List<Node> nodeList = new ArrayList<>();
                    nodeList.add(candidateNode);
                    Command deleteCmd = DeleteCommand.delete(nodeList, true);
                    if (deleteCmd != null) {
                        UndoRedoHandler.getInstance().add(deleteCmd);
                    }
                }


            } else if (candidateNode != null) {
                // Moving the highlighted node
                EastNorth nodeEN = candidateNode.getEastNorth();

                Node saveCandidateNode = candidateNode;
                UndoRedoHandler.getInstance().add(new MoveCommand(candidateNode, newPointEN.east() - nodeEN.east(), newPointEN.north()
                        - nodeEN.north()));
                candidateNode = saveCandidateNode;

            }
        }

        updateCursor();
        updateStatusLine();
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    protected void resetTimer() {
        if (longKeypressTimer != null) {
            try {
                longKeypressTimer.cancel();
                longKeypressTimer.purge();
            } catch (IllegalStateException exception) {
                Logging.debug(exception);
            }
        }
        longKeypressTimer = new Timer();
    }

    @Override
    public void doKeyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_WINDOWS) {
            mod4 = true;
            MainApplication.getLayerManager().invalidateEditLayer();
            return;
        }
        if (!helpersShortcut.isEvent(e) && !getShortcut().isEvent(e)) return;
        if (!isExpert) return;
        keypressTime = System.currentTimeMillis();
        helpersEnabledBeforeKeypressed = helpersEnabled;
        if (!helpersEnabled) helpersEnabled = true;
        helpersUseOriginal = true;
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    @Override
    public void doKeyReleased(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_WINDOWS) {
            mod4 = false;
            MainApplication.getLayerManager().invalidateEditLayer();
            return;
        }
        if (!helpersShortcut.isEvent(e) && !getShortcut().isEvent(e)) return;
        if (!isExpert) return;
        resetTimer();
        long keyupTime = System.currentTimeMillis();
        if (keypressTime == 0) { // comes from enterMode
            helpersEnabled = false;
        } else if (keyupTime-keypressTime > longKeypressTime) {
            helpersEnabled = helpersEnabledBeforeKeypressed;
        } else {
            helpersEnabled = !helpersEnabledBeforeKeypressed;
        }
        helpersUseOriginal = false;
        MainApplication.getLayerManager().invalidateEditLayer();
    }

    @Override
    public void expertChanged(boolean isExpert) {
        this.isExpert = isExpert;
        if (!isExpert && helpersEnabled) {
            helpersEnabled = false;
            MainApplication.getLayerManager().invalidateEditLayer();
        }
    }

    @Override
    public void preferenceChanged(PreferenceChangeEvent e) {
        super.preferenceChanged(e);
        if (isEnabled() && (e.getKey().startsWith("improvewayaccuracy") || e.getKey().startsWith("color.improve.way.accuracy"))) {
            MainApplication.getLayerManager().invalidateEditLayer();
        }
    }
}
