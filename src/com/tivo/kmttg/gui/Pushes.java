package com.tivo.kmttg.gui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.io.StringReader;
import java.util.Hashtable;
import java.util.Stack;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.tivo.kmttg.JSON.JSONArray;
import com.tivo.kmttg.JSON.JSONObject;
import com.tivo.kmttg.main.config;
import com.tivo.kmttg.mind.Mind;
import com.tivo.kmttg.rpc.Remote;
import com.tivo.kmttg.util.file;
import com.tivo.kmttg.util.log;
import com.tivo.kmttg.util.pyTivo;

public class Pushes {
   private String bodyId = null;
   private JFrame frame = null;
   private JDialog dialog = null;
   private pushTable tab = null;
   private JSONArray data = null;
   private Mind mind = null;
   
   public Pushes(String tivoName, JFrame frame) {
      this.frame = frame;
      
      // Determine bodyId = tsn for this TiVo
      bodyId = config.getTsn(tivoName);
      if (bodyId == null) {
         // Try using RPC to get tsn instead
         if (config.getRpcSetting(tivoName).equals("1")) {
            Remote r = new Remote(tivoName);
            if (r.success) {
               bodyId = r.bodyId_get();
               r.disconnect();
            }
         }
         if (bodyId == null) {
            log.error("tsn could not be determined for: " + tivoName);
            return;
         }
      }
      if ( ! bodyId.startsWith("tsn") )
         bodyId = "tsn:" + bodyId;
      
      // Determine mind server username & password by parsing pyTivo.conf
      if (! file.isFile(config.pyTivo_config)) {
         log.error("You have not configured valid path to pyTivo.conf file (needed for username & password)");
         return;
      }
      pyTivo.parsePyTivoConf(config.pyTivo_config);
      if (config.pyTivo_username == null || config.pyTivo_password == null) {
         log.error("pyTivo username and/or password not set in " + config.pyTivo_config);
         return;
      }
      
      // Query mind server for pushes and store in data
      getPushes();
   }
   
   // Retrieve queue data from TiVo mind server
   private void getPushes() {
      // Run in separate background thread
      class backgroundRun extends SwingWorker<Void, Void> {
         protected Void doInBackground() {
            if (tab != null)
               tab.clear();
            data = new JSONArray();
            if (mind == null)
               mind = new Mind(config.pyTivo_mind);
            if (!mind.login(config.pyTivo_username, config.pyTivo_password)) {
               log.error("Failed to login to Mind");
               return null;
            }
            Stack<String> s = mind.pcBodySearch();
            if (s != null && s.size() > 0) {
               String pcBodyId = mind.getElement(s.get(0), "pcBodyId");
               String command = "bodyOfferSearch";
               Hashtable<String,String> h = new Hashtable<String,String>();
               h.put("bodyId", bodyId);
               h.put("pcBodyId", pcBodyId);
               h.put("noLimit", "true");
               s = mind.dict_request(command + "&bodyId=" + bodyId, h);
               if (s != null && s.size() > 0) {
                  parseSearchXML(s.get(0));
               } else {
                  log.warn("No queued entries found");
               }
            } else {
               log.error("getPushes - Unable to retrieve pcBodyId");
               return null;
            }
            
            if (data != null && data.length() > 0) {
               if (dialog == null)
                  init();
               else
                  tab.AddRows(data);
            } else {
               log.warn("No pending pushes found to display");
            }
            return null;
         }
      }
      backgroundRun b = new backgroundRun();
      b.execute();
   }
   
   private void removePushes(JSONArray entries) {
      // Run in separate background thread
      class backgroundRun extends SwingWorker<Void, Void> {
         JSONArray entries;
         
         public backgroundRun(JSONArray entries) {
            this.entries = entries;
         }
         
         protected Void doInBackground() {
            try {
               Mind mind = new Mind(config.pyTivo_mind);
               if (!mind.login(config.pyTivo_username, config.pyTivo_password)) {
                  log.error("Failed to login to Mind");
                  return null;
               }
               for (int i=0; i<entries.length(); ++i) {
                  String command = "bodyOfferRemove";
                  Hashtable<String,String> h = new Hashtable<String,String>();
                  h.put("bodyId", bodyId);
                  h.put("bodyOfferId", entries.getJSONObject(i).getString("bodyOfferId"));
                  Stack<String> s = mind.dict_request(command + "&bodyId=" + bodyId, h);
                  if (s != null && s.size() > 0) {
                     log.print(s.get(0));
                  } else {
                     log.error("push item remove failed");
                     return null;
                  }
               }
            } catch (Exception e) {
               log.error("removePushes - " + e.getMessage());
               return null;
            }
            return null;
         }
      }
      backgroundRun b = new backgroundRun(entries);
      b.execute();
   }
   
   // Parse given bodyOfferList xml and populate data JSONArray with its contents
   private void parseSearchXML(String xml) {
      try {
         DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
         DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
         InputSource is = new InputSource();
         is.setCharacterStream(new StringReader(xml));
         Document doc = docBuilder.parse(is);
         if (doc == null) {
            log.error("Unable to parse xml");
            return;
         }
         NodeList entryList = doc.getElementsByTagName("bodyOffer");
         if (entryList != null && entryList.getLength() > 0) {
            data = new JSONArray();
            for (int i=0; i<entryList.getLength(); ++i) {
               JSONObject json = new JSONObject();
               for (Node childNode = entryList.item(i).getFirstChild(); childNode != null;) {
                  Node nextChild = childNode.getNextSibling();
                  json.put(childNode.getNodeName(), childNode.getTextContent());
                  childNode = nextChild;
               }
               data.put(json);
            }
         }
      } catch (Exception e) {
         log.error("parseSearchXML - " + e.getMessage());
      }
   }
   
   private void init() {
      // Define content for dialog window
      int gy = 0;
      JPanel content = new JPanel();
      content.setLayout(new GridBagLayout());
      GridBagConstraints c = new GridBagConstraints();
      c.ipady = 0;
      c.weighty = 0.0;  // default to no vertical stretch
      c.weightx = 0.0;  // default to no horizontal stretch
      c.gridx = 0;
      c.gridy = gy;
      c.gridwidth = 1;
      c.gridheight = 1;
      c.anchor = GridBagConstraints.CENTER;
      c.fill = GridBagConstraints.NONE;

      // Refresh button
      JButton refresh = new JButton("Refresh");
      String tip = "<html><b>Refresh</b><br>Query queued pushes and refresh table.<br>";
      tip += "NOTE: The mind server listings can be several seconds off compared to what is currently happening.";
      tip += "</html>";
      refresh.setToolTipText(tip);
      refresh.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent e) {
            getPushes();
         }
      });

      // Remove button
      JButton remove = new JButton("Remove");
      tip = "<html><b>Remove</b><br>Attempt to remove selected entry in the table from push queue.<br>";
      tip += "NOTE: This will not cancel pushes already in progress or very close to starting.<br>";
      tip += "NOTE: The response to this operation from mind server is always 'success' so there<br>";
      tip += "is no guarantee that removing an entry actually works or not.";
      tip += "</html>";
      remove.setToolTipText(tip);
      remove.addActionListener(new java.awt.event.ActionListener() {
         public void actionPerformed(java.awt.event.ActionEvent e) {
            JSONArray entries = new JSONArray();
            Boolean cont = true;
            while (cont) {
               int[] selected = TableUtil.GetSelectedRows(tab.getTable());
               if (selected.length > 0) {
                  int row = selected[0];
                  JSONObject json = tab.GetRowData(row);
                  if (json != null)
                     entries.put(json);
                  tab.RemoveRow(row);
               } else {
                  cont = false;
               }
            }
            if (entries.length() > 0)
               removePushes(entries);
         }
      });
      
      // Row 1 = 2 buttons
      JPanel row1 = new JPanel();
      row1.setLayout(new BoxLayout(row1, BoxLayout.LINE_AXIS));
      row1.add(refresh);
      row1.add(remove);
      content.add(row1, c);
      
      // Table
      tab = new pushTable();
      tab.AddRows(data);
      tab.getTable().setPreferredScrollableViewportSize(tab.getTable().getPreferredSize());
      JScrollPane tabScroll = new JScrollPane(tab.getTable());
      gy++;
      c.gridy = gy;
      c.weighty = 1.0;
      c.weightx = 1.0;
      c.fill = GridBagConstraints.BOTH;
      content.add(tabScroll, c);

      dialog = new JDialog(frame, false); // non-modal dialog
      dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE); // Destroy when closed
      dialog.setTitle("Push Queue");
      dialog.setContentPane(content);
      dialog.pack();
      dialog.setSize((int)(frame.getSize().width), (int)(frame.getSize().height/3));
      dialog.setLocationRelativeTo(config.gui.getJFrame().getJMenuBar().getComponent(0));
      dialog.setVisible(true);      
   }
}
