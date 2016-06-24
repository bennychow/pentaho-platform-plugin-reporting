/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License, version 2 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/gpl-2.0.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 *
 * Copyright 2006 - 2016 Pentaho Corporation.  All rights reserved.
 */

package org.pentaho.reporting.platform.plugin;

import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.jaxrs.impl.ResponseBuilderImpl;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.pentaho.platform.api.engine.IPentahoObjectFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.reporting.platform.plugin.async.AsyncExecutionStatus;
import org.pentaho.reporting.platform.plugin.async.AsyncReportState;
import org.pentaho.reporting.platform.plugin.async.IAsyncReportState;
import org.pentaho.reporting.platform.plugin.async.IPentahoAsyncExecutor;
import org.pentaho.reporting.platform.plugin.async.PentahoAsyncExecutor;
import org.pentaho.reporting.platform.plugin.staging.IFixedSizeStreamingContent;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

public class JobManagerTest {

  private static final String URL_FORMAT = "/reporting/api/jobs/%1$s%2$s";

  private static JaxRsServerProvider provider;
  private static PentahoAsyncExecutor executor = null;
  private static final UUID uuid = UUID.randomUUID();

  private static final String MIME = "junit_mime";
  public static final String PATH = "junit_path";
  private static int PROGRESS = -113;
  private static AsyncExecutionStatus STATUS = AsyncExecutionStatus.FAILED;

  private static IAsyncReportState STATE;

  @BeforeClass public static void setUp() throws Exception {
    provider = new JaxRsServerProvider();
    provider.startServer( new JobManager() );
    STATE = getState();
    executor = mock( PentahoAsyncExecutor.class );
    when( executor.getReportState( any( UUID.class ), any( IPentahoSession.class ) ) ).thenReturn( STATE );

    PentahoSystem.init();
    final IPentahoObjectFactory objFactory = mock( IPentahoObjectFactory.class );

    // return mock executor for any call to it's bean name.
    when( objFactory.objectDefined( anyString() ) ).thenReturn( true );
    when( objFactory.objectDefined( any( Class.class ) ) ).thenReturn( true );
    when( objFactory.get( any( Class.class ), eq( PentahoAsyncExecutor.BEAN_NAME ), any( IPentahoSession.class ) ) )
      .thenReturn( executor );

    PentahoSystem.registerObjectFactory( objFactory );
  }

  @AfterClass
  public static void tearDown() throws Exception {
    PentahoSystem.shutdown();
    provider.stopServer();
  }

  @Test public void testGetStatus() throws IOException {
    final WebClient client = provider.getFreshClient();
    client.path( String.format( URL_FORMAT, UUID.randomUUID().toString(), "/status" ) );
    final Response response = client.get();
    assertNotNull( response );
    assertTrue( response.hasEntity() );

    final String json = response.readEntity( String.class );

    // currently no simple way to restore to AsyncReportState interface here
    // at least we get uuid in return.
    assertTrue( json.contains( uuid.toString() ) );
  }

  @Test
  public void calculateContentDisposition() throws Exception {
    final IAsyncReportState state =
      new AsyncReportState( UUID.randomUUID(), "/somepath/anotherlevel/file.prpt", AsyncExecutionStatus.FINISHED, 0, 0,
        0, 0, 0, 0, "", "text/csv", "" );

    final Response.ResponseBuilder builder = new ResponseBuilderImpl();

    JobManager.calculateContentDisposition( builder, state );
    final Response resp = builder.build();
    final MultivaluedMap<String, String> stringHeaders = resp.getStringHeaders();
    assertTrue( stringHeaders.get( "Content-Description" ).contains( "file.prpt" ) );
    assertTrue( stringHeaders.get( "Content-Disposition" ).contains( "inline; filename*=UTF-8''file.csv" ) );
    resp.close();
  }


  @Test public void testRequestPage() throws IOException {
    final WebClient client = provider.getFreshClient();
    client.path( String.format( URL_FORMAT, UUID.randomUUID().toString(), "/requestPage/100" ) );

    final Response response = client.get();
    assertNotNull( response );
    assertTrue( response.hasEntity() );

    final String page = response.readEntity( String.class );

    assertEquals( "100", page );
  }

  @Test public void testSchedule() throws IOException {
    final WebClient client = provider.getFreshClient();
    client.path( String.format( URL_FORMAT, UUID.randomUUID().toString(), "/schedule" ) );
    final Response response = client.get();
    assertNotNull( response );
    assertEquals( 200, response.getStatus() );
  }

  @Test
  public void testInvalidUUID() {
    WebClient client = provider.getFreshClient();
    client.path( String.format( URL_FORMAT, null, "/requestPage/100" ) );
    final Response response1 = client.get();
    assertEquals( response1.getStatus(), 404 );
    client = provider.getFreshClient();
    client.path( String.format( URL_FORMAT, "", "/schedule" ) );
    final Response response2 = client.get();
    assertEquals( response2.getStatus(), 404 );
    client = provider.getFreshClient();
    client.path( String.format( URL_FORMAT, "not a uuid", "/status" ) );
    final Response response3 = client.get();
    assertEquals( response3.getStatus(), 404 );
  }


  @SuppressWarnings( "unchecked" )
  @Test
  public void testUpdateScheduleLocation() throws Exception {
    try {


      provider.stopServer();
      provider.startServer( new JobManager( true, 1000, 1000, true ) );

      STATUS = AsyncExecutionStatus.SCHEDULED;

      WebClient client = provider.getFreshClient();
      final UUID folderId = UUID.randomUUID();
      final String config = String.format( URL_FORMAT, uuid, "/schedule/location" );
      client.path( config );
      client.query( "folderId", folderId );
      client.query( "newName", "test" );

      Response response = client.post( null );
      assertEquals( 200, response.getStatus() );
      verify( executor, times( 1 ) )
        .updateSchedulingLocation( any(), any(), any(), any() );

      STATUS = AsyncExecutionStatus.FAILED;
    } finally {
      provider.stopServer();
      provider.startServer( new JobManager() );
    }
  }


  @SuppressWarnings( "unchecked" )
  @Test
  public void testUpdateScheduleLocationNotScheduled() throws Exception {
    try {


      provider.stopServer();
      provider.startServer( new JobManager( true, 1000, 1000, true ) );

      final IPentahoAsyncExecutor executor = PentahoSystem.get( IPentahoAsyncExecutor.class );

      WebClient client = provider.getFreshClient();
      final UUID folderId = UUID.randomUUID();
      final String config = String.format( URL_FORMAT, uuid, "/schedule/location" );
      client.path( config );
      client.query( "folderId", folderId );

      Response response = client.post( null );
      assertEquals( 404, response.getStatus() );


      STATUS = AsyncExecutionStatus.FAILED;
    } finally {
      provider.stopServer();
      provider.startServer( new JobManager() );
    }
  }

  @SuppressWarnings( "unchecked" )
  @Test
  public void testUpdateScheduleLocationDisabledPrompting() throws Exception {

    WebClient client = provider.getFreshClient();
    final UUID folderId = UUID.randomUUID();
    final String config = String.format( URL_FORMAT, uuid, "/schedule/location" );
    client.path( config );
    client.query( "folderId", folderId );

    Response response = client.post( null );
    assertEquals( 404, response.getStatus() );

    STATUS = AsyncExecutionStatus.FAILED;
  }

  @Test
  public void testGetExec() throws Exception {
    final JobManager jobManager = new JobManager();
    assertEquals( jobManager.getExecutor(), executor );
  }

  @Test public void testCancel() throws IOException {

    final UUID uuid = UUID.randomUUID();
    final WebClient client = provider.getFreshClient();

    final Future future = mock( Future.class );
    when( executor.getFuture( uuid, PentahoSessionHolder.getSession() ) ).thenReturn( future );
    final String config = String.format( URL_FORMAT, uuid, "/cancel" );


    client.path( config );

    final Response response = client.get();
    assertNotNull( response );
    assertEquals( 200, response.getStatus() );

    verify( future, times( 1 ) ).cancel( true );

  }


  @Test public void testContent() throws IOException, ExecutionException, InterruptedException {
    final UUID uuid = UUID.randomUUID();
    final WebClient client = provider.getFreshClient();

    final Future future = mock( Future.class );
    final IFixedSizeStreamingContent content = mock( IFixedSizeStreamingContent.class );
    when( future.get() ).thenReturn( content );
    when( executor.getFuture( uuid, PentahoSessionHolder.getSession() ) ).thenReturn( future );
    final String config = String.format( URL_FORMAT, uuid, "/content" );


    client.path( config );

    final Response response = client.get();
    assertNotNull( response );
    assertEquals( 202, response.getStatus() );

    STATUS = AsyncExecutionStatus.FINISHED;

    final Response response1 = client.get();

    assertNotNull( response1 );
    assertEquals( 200, response1.getStatus() );

  }

  public static IAsyncReportState getState() {
    return new IAsyncReportState() {

      @Override public String getPath() {
        return PATH;
      }

      @Override public UUID getUuid() {
        return uuid;
      }

      @Override public AsyncExecutionStatus getStatus() {
        return STATUS;
      }

      @Override public int getProgress() {
        return PROGRESS;
      }

      @Override public int getPage() {
        return 0;
      }

      @Override public int getTotalPages() {
        return 0;
      }

      @Override public int getGeneratedPage() {
        return 0;
      }

      @Override public int getRow() {
        return 0;
      }

      @Override public int getTotalRows() {
        return 0;
      }

      @Override public String getActivity() {
        return null;
      }

      @Override public String getMimeType() {
        return MIME;
      }

      @Override public String getErrorMessage() {
        return null;
      }

    };
  }
}