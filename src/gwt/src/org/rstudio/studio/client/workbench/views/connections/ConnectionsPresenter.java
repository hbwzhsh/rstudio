/*
 * ConnectionsPresenter.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * This program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */
package org.rstudio.studio.client.workbench.views.connections;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.HasClickHandlers;
import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.event.logical.shared.ValueChangeHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.view.client.SelectionChangeEvent;
import com.google.inject.Inject;

import org.rstudio.core.client.Debug;
import org.rstudio.core.client.ListUtil;
import org.rstudio.core.client.ListUtil.FilterPredicate;
import org.rstudio.core.client.command.CommandBinder;
import org.rstudio.core.client.command.Handler;
import org.rstudio.core.client.events.EnsureHeightEvent;
import org.rstudio.core.client.js.JsObject;
import org.rstudio.studio.client.common.GlobalDisplay;
import org.rstudio.studio.client.workbench.WorkbenchListManager;
import org.rstudio.studio.client.workbench.WorkbenchView;
import org.rstudio.studio.client.workbench.commands.Commands;
import org.rstudio.studio.client.workbench.model.ClientState;
import org.rstudio.studio.client.workbench.model.Session;
import org.rstudio.studio.client.workbench.model.helper.JSObjectStateValue;
import org.rstudio.studio.client.workbench.views.BasePresenter;
import org.rstudio.studio.client.workbench.views.connections.events.ConnectionClosedEvent;
import org.rstudio.studio.client.workbench.views.connections.events.ConnectionOpenedEvent;
import org.rstudio.studio.client.workbench.views.connections.events.ConnectionUpdatedEvent;
import org.rstudio.studio.client.workbench.views.connections.events.ExploreConnectionEvent;
import org.rstudio.studio.client.workbench.views.connections.model.Connection;
import org.rstudio.studio.client.workbench.views.connections.model.ConnectionList;
import org.rstudio.studio.client.workbench.views.connections.model.ConnectionsServerOperations;

public class ConnectionsPresenter extends BasePresenter 
{
   public interface Display extends WorkbenchView
   {
      void setConnections(List<Connection> connections);
      
      Connection getSelectedConnection();
          
      HandlerRegistration addSelectedConnectionChangeHandler(
                                 SelectionChangeEvent.Handler handler);
      
      HandlerRegistration addSearchFilterChangeHandler(
                                       ValueChangeHandler<String> handler);
      
      HandlerRegistration addExploreConnectionHandler(
                                       ExploreConnectionEvent.Handler handler);
      
      void showConnectionExplorer(Connection connection);
      
      
      HasClickHandlers backToConnectionsButton();
      
      void showConnectionsList();
   }
   
   public interface Binder extends CommandBinder<Commands, ConnectionsPresenter> {}
   
   @Inject
   public ConnectionsPresenter(Display display, 
                               ConnectionsServerOperations server,
                               GlobalDisplay globalDisplay,
                               Binder binder,
                               final Commands commands,
                               WorkbenchListManager listManager,
                               Session session)
   {
      super(display);
      binder.bind(commands, this);
      display_ = display;
      server_ = server;
      globalDisplay_ = globalDisplay;
      connectionList_ = new ConnectionList(listManager.getConnectionsList());
        
      // start off with connect/disconnect commands invisible then
      // change them with the active selection
      commands.connectConnection().setVisible(false);
      commands.disconnectConnection().setVisible(false);
      
      // track selected connection
      display_.addSelectedConnectionChangeHandler(
                                       new SelectionChangeEvent.Handler() {
         @Override
         public void onSelectionChange(SelectionChangeEvent event)
         {
            boolean isConnected = false;
            commands.connectConnection().setVisible(!isConnected);
            commands.disconnectConnection().setVisible(isConnected);
         }
      });
      
      // search filter
      display_.addSearchFilterChangeHandler(new ValueChangeHandler<String>() {

         @Override
         public void onValueChange(ValueChangeEvent<String> event)
         {
            String query = event.getValue();
            final String[] splat = query.toLowerCase().split("\\s+");
            List<Connection> connections = ListUtil.filter(allConnections_, 
                                         new FilterPredicate<Connection>()
            {
               @Override
               public boolean test(Connection connection)
               {
                  for (String el : splat)
                  {
                     boolean match =
                         connection.getName().toLowerCase().contains(el);
                     if (!match)
                        return false;
                  }
                  return true;
               }
            });
            display_.setConnections(connections);
         }
      });
      
      display_.addExploreConnectionHandler(new ExploreConnectionEvent.Handler()
      {   
         @Override
         public void onExploreConnection(ExploreConnectionEvent event)
         {
            exploreConnection(event.getConnection());
            display_.ensureHeight(EnsureHeightEvent.MAXIMIZED);
         }
      });
      
      display_.backToConnectionsButton().addClickHandler(new ClickHandler() {

         @Override
         public void onClick(ClickEvent event)
         {
            activeConnection_ = null;
            display_.showConnectionsList();
            display_.ensureHeight(EnsureHeightEvent.NORMAL);
         }
         
      });
      
      // fake connection data for now
      ArrayList<Connection> connections = new ArrayList<Connection>();
      //connections.add(Connection.create("Spark", "localhost:4040", true));
      //connections.add(Connection.create("Spark", "localhost:4141", false));
      //connections.add(Connection.create("Spark", "localhost:4242", false));
      updateConnections(connections);  
      
      // make the active connection persistent
      new JSObjectStateValue(MODULE_CONNECTIONS, 
                             KEY_ACTIVE_CONNECTION, 
                             ClientState.PERSISTENT, 
                             session.getSessionInfo().getClientState(), 
                             false)
      {
         @Override
         protected void onInit(JsObject value)
         {
            // get the value
            if (value != null)
               activeConnection_ = value.cast();
            else
               activeConnection_ = null;
                 
            lastKnownActiveConnection_ = activeConnection_;
            
            // if there is an active connection then explore it
            if (activeConnection_ != null)
               exploreConnection(activeConnection_);
         }

         @Override
         protected JsObject getValue()
         {
            if (activeConnection_ != null)
               return activeConnection_.cast();
            else
               return null;
         }

         @Override
         protected boolean hasChanged()
         {
            if (lastKnownActiveConnection_ != activeConnection_)
            {
               lastKnownActiveConnection_ = activeConnection_;
               return true;
            }
            else
            {
               return false;
            }
         }
      };
   }
   
   public void onConnectionOpened(ConnectionOpenedEvent event)
   {
      Debug.logToConsole("Connection Opened: " + 
                         event.getConnection().getId().asString());  
   }
   
   public void onConnectionClosed(ConnectionClosedEvent event)
   {
      Debug.logToConsole("Connection Closed: " + 
                         event.getConnectionId().asString());         
   }
   
   public void onConnectionUpdated(ConnectionUpdatedEvent event)
   {
      Debug.logToConsole("Connection Updated: " + 
                         event.getConnectionId().asString());         
   }
   
   public void onNewConnection()
   {
      globalDisplay_.showErrorMessage("Error", "Not Yet Implemented");
   }
   
   @Handler
   public void onRemoveConnection()
   {
      globalDisplay_.showErrorMessage("Error", "Not Yet Implemented");
   }
  
   
   @Handler
   public void onConnectConnection()
   {
      globalDisplay_.showErrorMessage("Error", "Not Yet Implemented");
   }
   
   @Handler
   public void onDisconnectConnection()
   {
      globalDisplay_.showErrorMessage("Error", "Not Yet Implemented");
   }
   
   private void updateConnections(List<Connection> connections)
   {
      allConnections_ = connections;
      display_.setConnections(allConnections_);
   }
   
   private void exploreConnection(Connection connection)
   {
      activeConnection_ = connection;
      display_.showConnectionExplorer(connection);
   }
   
   private final GlobalDisplay globalDisplay_;
   
   private final Display display_ ;
   @SuppressWarnings("unused")
   private final ConnectionsServerOperations server_ ;
   
   // client state
   private static final String MODULE_CONNECTIONS = "connections-pane";
   private static final String KEY_ACTIVE_CONNECTION = "activeConnection";
   private Connection activeConnection_;
   private Connection lastKnownActiveConnection_;
   
   @SuppressWarnings("unused")
   private ConnectionList connectionList_;

   private List<Connection> allConnections_;
}