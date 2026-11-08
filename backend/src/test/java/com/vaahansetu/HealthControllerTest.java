package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vaahansetu.controller.HealthController;

class HealthControllerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private HealthController healthController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        healthController = new HealthController(dataSource);
        mockMvc = MockMvcBuilders.standaloneSetup(healthController).build();
    }

    @Test
    @DisplayName("Liveness endpoint returns HTTP 200 with status UP and never queries the database")
    void testLiveness_ReturnsHttp200_WithoutQueryingDatabase() {
        ResponseEntity<Map<String, Object>> response = healthController.getLiveness();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));

        // Strictly verify DataSource was never called
        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("Readiness endpoint returns HTTP 200 with database UP when connection is valid")
    void testReadiness_ReturnsHttp200_WhenDatabaseIsHealthy() throws SQLException {
        ResponseEntity<Map<String, Object>> response = healthController.getReadiness();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("UP", response.getBody().get("database"));

        verify(dataSource, times(1)).getConnection();
        verify(connection, times(1)).createStatement();
        verify(statement, times(1)).executeQuery("SELECT 1");
    }

    @Test
    @DisplayName("Readiness endpoint returns HTTP 503 when obtaining database connection fails")
    void testReadiness_ReturnsHttp503_WhenDatabaseConnectionFails() throws SQLException {
        when(dataSource.getConnection())
                .thenThrow(new SQLException("FATAL: connection to server at ep-rough-mode.neon.tech failed"));

        ResponseEntity<Map<String, Object>> response = healthController.getReadiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
        assertEquals("Database service is currently unavailable.", response.getBody().get("message"));

        // Ensure database host, connection string, credentials, and SQL exception are NEVER leaked
        assertFalse(response.getBody().toString().contains("neon.tech"));
        assertFalse(response.getBody().toString().contains("FATAL"));
    }

    @Test
    @DisplayName("Readiness endpoint returns HTTP 503 when query execution throws an exception")
    void testReadiness_ReturnsHttp503_WhenQueryExecutionFails() throws SQLException {
        when(statement.executeQuery("SELECT 1"))
                .thenThrow(new SQLException("Query timed out or connection terminated"));

        ResponseEntity<Map<String, Object>> response = healthController.getReadiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
        assertEquals("Database service is currently unavailable.", response.getBody().get("message"));

        // Ensure internal SQL exception message is NOT leaked
        assertFalse(response.getBody().toString().contains("Query timed out"));
    }

    @Test
    @DisplayName("Readiness endpoint returns HTTP 503 when ResultSet is unexpectedly empty")
    void testReadiness_ReturnsHttp503_WhenResultSetIsEmpty() throws SQLException {
        when(resultSet.next()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = healthController.getReadiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
    }

    @Test
    @DisplayName("Readiness endpoint returns HTTP 503 when DataSource is null")
    void testReadiness_ReturnsHttp503_WhenDataSourceIsNull() {
        HealthController nullDsController = new HealthController(null);
        ResponseEntity<Map<String, Object>> response = nullDsController.getReadiness();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("DOWN", response.getBody().get("status"));
        assertEquals("DOWN", response.getBody().get("database"));
    }

    @Test
    @DisplayName("MockMvc GET /api/health/liveness returns HTTP 200 with JSON payload")
    void testMockMvc_LivenessRoute() throws Exception {
        mockMvc.perform(get("/api/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        verifyNoInteractions(dataSource);
    }

    @Test
    @DisplayName("MockMvc GET /api/health/readiness returns HTTP 200 when database is UP")
    void testMockMvc_ReadinessRoute_Success() throws Exception {
        mockMvc.perform(get("/api/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    @DisplayName("MockMvc GET /api/health/readiness returns HTTP 503 when database is unavailable")
    void testMockMvc_ReadinessRoute_Failure() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("Neon wake-up pending"));

        mockMvc.perform(get("/api/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"))
                .andExpect(jsonPath("$.message").value("Database service is currently unavailable."));
    }
}
