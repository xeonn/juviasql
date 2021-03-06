/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package my.onn.jdbcadmin.browser;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javax.inject.Inject;
import static my.onn.jdbcadmin.browser.BrowserItemType.*;
import my.onn.jdbcadmin.sqleditor.SqlEditorWindow;
import my.onn.jdbcadmin.connection.ConnectionModel;
import my.onn.jdbcadmin.ui.util.FxmlControllerProducer;
import my.onn.jdbcadmin.ui.util.FxmlStage;
import my.onn.jdbcadmin.ui.util.FxmlUI;

/**
 * FXML Controller class
 *
 * @author onn
 */
public class BrowserController extends FxmlStage {

    private static final Logger logger = Logger.getLogger(BrowserController.class.getName());

    /**
     * Model representation for TreeView component.
     *
     * Mostly modification of item will be done at the model object. The
     * treeViewResult component are refreshed with this model only.
     */
    BrowserItem model;// = new BrowserItem("", "empty", "No item selected", BrowserItemType.SERVER);

    ConnectionModel connectionModel;

    @Inject
    FxmlControllerProducer fxmlControllerProducer;

    @FXML
    private Button buttonRefresh;
    @FXML
    private Button buttonSqlEditor;
    @FXML
    private Button buttonTable;
    @FXML
    private StackPane leftStackPane;
    @FXML
    private TreeView<BrowserItem> treeView;
    @FXML
    private VBox vboxProperty;

    /**
     * Initializes the controller class.
     */
    public void initialize() {
        // Connection not yet available here
        buttonSqlEditor.setDisable(true);
        buttonTable.setDisable(true);

        /*
        Get Connection for Sql editor from selected tree item.
        Disable sql button if no child item selected or when root item is selected
         */
        treeView.getSelectionModel().selectedIndexProperty().addListener((obj, oldV, newV) -> {
            if (newV.intValue() > 0) {
                if (treeView.getSelectionModel().getSelectedItem().getParent() == null) {
                    buttonSqlEditor.setDisable(true);
                    // TODO : buttonTable enable/disable should happens at database table treeitem only
                    buttonTable.setDisable(true);
                } else {
                    buttonSqlEditor.setDisable(false);
                    buttonTable.setDisable(false);
                }
            } else {
                buttonSqlEditor.setDisable(true);
                buttonTable.setDisable(true);
            }

            showProperty(newV);
        });

    }

    private void showProperty(Number treeItemIndex) {
        vboxProperty.getChildren().clear();
        logger.info(String.format("Index item %d selected", treeItemIndex));

        if (treeItemIndex.intValue() > 0) {
            BrowserItem val = treeView.getSelectionModel().getSelectedItem().getValue();

            Label labelDesc = new Label("Description");
            labelDesc.setMinWidth(100);
            Label desc = new Label(val.getDescription());

            HBox hbox = new HBox(labelDesc, desc);
            vboxProperty.getChildren().add(hbox);
        }

        if (treeItemIndex.intValue() == 0) {
            // show connection property
            Label labelName = new Label("Name");
            Label name = new Label(connectionModel.getName());
            labelName.setMinWidth(80);
            HBox row1 = new HBox(labelName, name);

            Label labelHost = new Label("Host");
            Label host = new Label(connectionModel.getHost());
            labelHost.setMinWidth(80);
            HBox row2 = new HBox(labelHost, host);

            Label labelDatabase = new Label("Database");
            Label database = new Label(connectionModel.getMaintenanceDb());
            labelDatabase.setMinWidth(80);
            HBox row3 = new HBox(labelDatabase, database);

            Label labelUsername = new Label("User");
            Label username = new Label(connectionModel.getUsername());
            labelUsername.setMinWidth(80);
            HBox row4 = new HBox(labelUsername, username);

            Label labelUrl = new Label("Jdbc url");
            Label url = new Label(connectionModel.getUrl(null));
            labelUrl.setMinWidth(80);
            HBox row5 = new HBox(labelUrl, url);

            vboxProperty.getChildren().addAll(row1, row2, row3, row4, row5);
        }
    }

    /**
     * Set and activate connection for the browser window.
     *
     * @param model
     */
    public void setConnectionModel(ConnectionModel model) {
        if (this.connectionModel == null) {
            this.connectionModel = model;
            onActionButtonRefresh(null);
        } else {
            logger.warning(String.format("Connection exist [%s]. Ignoring new connection set %s",
                    this.connectionModel.getUrl(null), model.getUrl(null)));
        }
    }

    private VBox startTreeViewProgressIndicator() {
        ProgressIndicator pi = new ProgressIndicator();
        Label label = new Label("Connecting to database ...");
        VBox vbox = new VBox(pi, label);

        vbox.setAlignment(Pos.TOP_CENTER);
        vbox.setPadding(new Insets(10.0));
        vbox.setSpacing(10.0);

        leftStackPane.getChildren().add(vbox);

        return vbox;
    }

    /**
     * Retrieve and populate treeview using a worker thread.
     *
     * The structure of database display to user will be using a standard format
     * CATALOG - SCHEMA - TABLES/VIEW/PROCEDURE - COLUMNS
     *
     * @return
     */
    private CompletableFuture fetchModel() {
        return CompletableFuture.runAsync(() -> {
            logger.info("Worker thread begin Connection to " + connectionModel.getMaintenanceUrl());
            try (Connection cnn = DriverManager.getConnection(
                    connectionModel.getMaintenanceUrl(),
                    connectionModel.getUsername(),
                    connectionModel.getPassword())) {

                model = new BrowserItem(
                        connectionModel.getUrl(null),
                        String.format("%s\n[%s:%d]",
                                connectionModel.getName(),
                                connectionModel.getHost(),
                                connectionModel.getPort()),
                        "Server (root item)",
                        BrowserItemType.SERVER);

                DatabaseTree visitor = DatabaseTree.get(connectionModel);

                // Database
                PreparedStatement stmt = cnn.prepareStatement(
                        visitor.getCatalogSql(),
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);

                ResultSet catalogRS = stmt.executeQuery();

                List<BrowserItem> catalogs = visitor.getCatalogItems(catalogRS);
                catalogs.stream().forEach(catalog -> {

                    try (Connection cnnDb = DriverManager.getConnection(connectionModel.getUrl(catalog.getValue()),
                            connectionModel.getUsername(), connectionModel.getPassword())) {

                        logger.info("Catalog " + catalog.getValue() + " retrieved via " + connectionModel.getUrl(catalog.getValue()));
                        ResultSet schemaRS = cnnDb.getMetaData().getSchemas(catalog.getValue(), null);
                        List<BrowserItem> schemas = visitor.getSchemaItems(schemaRS, catalog);
                        schemas.stream().forEach(schema -> {
                            try {
                                BrowserItem si = schema;

                                /* Add TABLE to schema */
                                ObservableList<BrowserItem> tableList = FXCollections.observableArrayList();

                                String[] ttype = {"SYSTEM TABLE", "TABLE"};
                                ResultSet tables = cnnDb.getMetaData().getTables(catalog.getValue(), schema.getValue(), "%", ttype);
                                while (tables.next()) {

                                    logger.info("Retrieving information for table : " + tables.getString(3));

                                    String table = tables.getString(3);

                                    ObservableList<BrowserItem> columnList = FXCollections.observableArrayList();

                                    //Columns
                                    ResultSet columns = cnnDb.getMetaData().getColumns(null, null, table, "%");
                                    while (columns.next()) {

                                        BrowserItem ci = addColumnItem(columns);

                                        columnList.add(ci); // Add column to tables
                                    }
                                    BrowserItem tti = new BrowserItem(table,
                                            String.format("%s (%d cols)", table, columnList.size()),
                                            String.format("%s\nNo of Columns: %d",
                                                    tables.getString(3),
                                                    columnList.size()),
                                            TABLE);
                                    tti.getChildren().addAll(columnList);

                                    tableList.add(tti); // Add tables to table parent
                                }
                                if (!tableList.isEmpty()) // Add table parent to schema if any
                                {
                                    BrowserItem ti = new BrowserItem("Tables", String.format("Table (%d)", tableList.size()),
                                            "Tables", ALL_TABLE);
                                    ti.getChildren().addAll(tableList);
                                    si.getChildren().add(ti);
                                }

                                /* Add VIEW to schema */
                                ObservableList<BrowserItem> viewList = FXCollections.observableArrayList();
                                String[] vtype = {"VIEW"};
                                ResultSet views = cnnDb.getMetaData().getTables(catalog.getValue(), schema.getValue(), "%", vtype);
                                while (views.next()) {
                                    String view = views.getString(3);

                                    //Columns
                                    ObservableList<BrowserItem> columnList = FXCollections.observableArrayList();
                                    ResultSet columns = cnnDb.getMetaData().getColumns(null, null, view, "%");
                                    while (columns.next()) {

                                        BrowserItem ci = addColumnItem(columns);
                                        columnList.add(ci); // Add column to tables
                                    }
                                    BrowserItem vvi = new BrowserItem(view,
                                            String.format("%s (%d cols)", view, columnList.size()),
                                            String.format("%s\nNo of Columns: %d",
                                                    views.getString(3),
                                                    columnList.size()),
                                            VIEW);
                                    vvi.getChildren().addAll(columnList);
                                    viewList.add(vvi);
                                }
                                if (!viewList.isEmpty()) {
                                    BrowserItem vi = new BrowserItem("View", String.format("Views (%d)", viewList.size()),
                                            "Tables", ALL_VIEW);
                                    vi.getChildren().addAll(viewList);
                                    si.getChildren().add(vi);
                                }

                                if (!si.getChildren().isEmpty()) // Add schema to catalog (if any)
                                {
                                    catalog.getChildren().add(si);
                                }

                            } catch (SQLException ex) {
                                Logger.getLogger(BrowserController.class.getName()).log(Level.SEVERE, null, ex);
                            }
                        }); //Schema
                        if (!catalog.getChildren().isEmpty()) {
                            model.getChildren().add(catalog);
                        }
                    } catch (SQLException ex) {
                        Logger.getLogger(BrowserController.class.getName()).log(Level.SEVERE, null, ex);
                    }
                });
            } catch (SQLException ex) {
                Logger.getLogger(BrowserController.class.getName()).log(Level.SEVERE, null, ex);
            }

        }).exceptionally(e -> {
            logger.log(Level.SEVERE, e.getLocalizedMessage());
            return null;
        });
    }

    private BrowserItem addColumnItem(ResultSet columns) throws SQLException, NumberFormatException {
        BrowserItem ci = new BrowserItem(columns.getString(6), String.format("%s [%s(%s)]",
                columns.getString(4), columns.getString(6), columns.getString(7)),
                String.format("%s\n%s(%s)\nNullable : %s",
                        columns.getString(4), columns.getString(6), columns.getString(7),
                        columns.getString(9) == null || Integer.parseInt(columns.getString(9)) > 0 ? "Yes" : "No"),
                COLUMN);
        return ci;
    }

    private void refreshTree() {
        buttonRefresh.setDisable(true);
        VBox vbox = startTreeViewProgressIndicator();

        fetchModel().thenRun(() -> Platform.runLater(() -> {
            // Fill up TreeView children from model
            TreeItem rootItem = new TreeItem<>(model);
            rootItem.setGraphic(new ImageView(model.getBrowserItemType().getIcon()));
            treeView.setRoot(rootItem);
            addTreeItemRecursive(model, rootItem);
            treeView.refresh();
            leftStackPane.getChildren().remove(vbox);
            buttonRefresh.setDisable(false);
        }));

    }

    private void addTreeItemRecursive(BrowserItem browserItem, TreeItem<BrowserItem> treeItem) {
        for (BrowserItem sub : browserItem.getChildren()) {
            TreeItem<BrowserItem> subItem = new TreeItem<>(sub);
            subItem.setValue(sub);
            subItem.setGraphic(new ImageView(sub.getBrowserItemType().getIcon()));
            treeItem.getChildren().add(subItem);

            addTreeItemRecursive(sub, subItem);
        }
    }

    /**
     * UI handling element
     *
     * @param item
     * @return
     */
    private boolean isParentRoot(TreeItem item) {
        return item.getParent().getParent() == null;
    }

    /**
     * UI handling element
     *
     * @param item
     * @return
     */
    private TreeItem getDatabaseTreeItem(TreeItem item) {
        /*If the root item itself is selected, sql editor button should have
        been disabled and this function never called.
         */
        if (item == null) {
            throw new IllegalStateException("This denotes a bug. Please contact developer");
        }
        if (isParentRoot(item)) {
            return item;
        } else {
            return getDatabaseTreeItem(item.getParent());
        }
    }

    @FXML
    private void onActionButtonSqlEditor(ActionEvent event) throws IOException {
        /* Sql Editor shall work only with valid connection url*/
        String database;
        // find database of selected item url
        TreeItem selectedItem = treeView.getSelectionModel().getSelectedItem();
        BrowserItem bItem = (BrowserItem) getDatabaseTreeItem(selectedItem).getValue();
        database = bItem.getValue();

        SqlEditorWindow wnd = (SqlEditorWindow) fxmlControllerProducer.getFxmlDialog(FxmlUI.SQLEDITOR);
        wnd.setConnectionUrl(connectionModel.getUrl(database), connectionModel.getUsername(), connectionModel.getPassword());
        // wnd.initOwner(this); -- this line is required but currently it disabled resizing and maximizing
        wnd.show();
    }

    @FXML
    private void onActionButtonRefresh(ActionEvent event) {
        // TODO : Refresh action should refresh portion of tree item. For now, we refresh all.

        treeView.setRoot(null);

        try (Connection cnn = DriverManager.getConnection(
                connectionModel.getUrl(null),
                connectionModel.getUsername(),
                connectionModel.getPassword())) {
            if (!cnn.isValid(2)) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Notification");
                alert.setContentText("Connection failed");
                alert.showAndWait();
            } else {
                refreshTree();
            }
        } catch (SQLException ex) {
            logger.log(Level.SEVERE, null, ex);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setContentText(ex.getLocalizedMessage());
            alert.showAndWait();
            this.close();
        }
    }

}
