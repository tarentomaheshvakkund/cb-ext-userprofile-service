package com.igot.cb.profile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.commons.util.CollectionUtils;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.profile.service.ProfileServiceImpl;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.transactional.redis.cache.CacheService;
import com.igot.cb.util.ApiResponse;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.Constants;
import com.igot.cb.util.ProjectUtil;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
public class ProfileServiceImplTest {
    @Mock
    private AccessTokenValidator accessTokenValidator;
    @Mock
    private CbServerProperties serverProperties;
    @Mock
    private CassandraOperation cassandraOperation;
    @Mock
    private CacheService cacheService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ProjectUtil projectUtil;


    @InjectMocks
    private ProfileServiceImpl profileService;

    private final String USER_ID = "user-123";
    private final String TOKEN = "dummy-token";
    private static final String CACHE_KEY = "user:competencies:user123";
    private final String [] CONTEXT_TYPE = {"contextA"};
    private static final String REDIS_KEY = "user:extendedProfile:project:user-123";
    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    static class TestContext {
        String contextKey;
        String dateField;
        List<Map<String, Object>> testData;

        TestContext(String contextKey, String dateField, List<Map<String, Object>> testData) {
            this.contextKey = contextKey;
            this.dateField = dateField;
            this.testData = testData;
        }
    }

    static Stream<TestContext> contextProvider() {
        return Stream.of(
                new TestContext(
                        Constants.SERVICE_HISTORY,
                        "startDate",
                        List.of(
                                new HashMap<>(Map.of("startDate", "2019-01-01T00:00:00Z", "dummyField", "dummyValue")),
                                new HashMap<>(Map.of("startDate", "2023-06-15T00:00:00Z", "dummyField", "dummyValue")),
                                new HashMap<>(Map.of("startDate", "2020-09-10T00:00:00Z", "dummyField", "dummyValue"))
                        )
                ),
                new TestContext(
                        Constants.ACHIEVEMENTS,
                        "issuedDate",
                        List.of(
                                new HashMap<>(Map.of("issuedDate", "2019-01-01T00:00:00Z", "dummyField", "dummyValue")),
                                new HashMap<>(Map.of("issuedDate", "2023-06-15T00:00:00Z", "dummyField", "dummyValue")),
                                new HashMap<>(Map.of("issuedDate", "2020-09-10T00:00:00Z", "dummyField", "dummyValue"))
                        )
                ),
                new TestContext(
                        Constants.EDUCATION_QUALIFICATION,
                        "startYear",
                        List.of(
                                new HashMap<>(Map.of("startYear", "2019", "dummyField", "dummyValue")),
                                new HashMap<>(Map.of("startYear", "2023", "dummyField", "dummyValue")),
                                new HashMap<>(Map.of("startYear", "2020", "dummyField", "dummyValue"))
                        )
                )
        );
    }


    @Test
    public void testGetBasicProfile_invalidToken_returnsError() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(null);

        ApiResponse response = profileService.getBasicProfile(USER_ID, TOKEN);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    public void testGetExtendedProfileSummary_noCache_fallsBackToDB() throws Exception {
        String[] contextTypes = { "education" };
        List<Map<String, Object>> dataList = List.of(Map.of("field", "value"));

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(anyString())).thenReturn(null);
        when(serverProperties.getContextType()).thenReturn(contextTypes);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[{\"field\":\"value\"}]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(dataList);

        ApiResponse response = profileService.getExtendedProfileSummary(USER_ID, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESPONSE));
    }

    @Test
    public void testSaveExtendedProfile_validInput_shouldSucceed() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("field1", "value1");
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put(Constants.USER_ID_RQST, USER_ID);
        requestMap.put("education", List.of(data));

        Map<String, Object> request = new HashMap<>(); 
        request.put(Constants.REQUEST, requestMap);

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[] { "education" });
        when(serverProperties.getEducationalQualificationMandatoryFields()).thenReturn("");
        when(serverProperties.getAchievementsMandatoryFields()).thenReturn("");
        when(serverProperties.getServiceHistoryMandatoryFields()).thenReturn("");
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), anyMap(), any(), any()))
                .thenReturn(new ArrayList<>());
        when(cassandraOperation.insertRecord(any(), any(), any()))
                .thenReturn(mockResponse);

        ApiResponse response = profileService.saveExtendedProfile(request, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESULT));
    }

    @Test
    public void testUpdateExtendedProfile_valid_shouldSucceed() throws Exception {
        String uuid = UUID.randomUUID().toString();
        Map<String, Object> incoming = new HashMap<>();
        incoming.put(Constants.UUID, uuid);
        incoming.put("key", "newVal");

        Map<String, Object> existing = new HashMap<>();
        existing.put(Constants.UUID, uuid);
        existing.put("key", "oldVal");

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put(Constants.USER_ID_RQST, USER_ID);
        requestMap.put("education", List.of(incoming));
        Map<String, Object> request = Map.of(Constants.REQUEST, requestMap);

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[] { "education" });
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), anyMap(), any(), any()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(List.of(existing));
        when(cassandraOperation.insertRecord(any(), any(), any()))
                .thenReturn(mockResponse);

        ApiResponse response = profileService.updateExtendedProfile(request, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
    }

    @Test
    public void testDeleteExtendedProfile_valid_shouldSucceed() throws Exception {
        String uuid = UUID.randomUUID().toString();
        Map<String, Object> deleteItem = Map.of(Constants.UUID, uuid);
        Map<String, Object> existingItem = Map.of(Constants.UUID, uuid, "key", "value");

        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put(Constants.USER_ID_RQST, USER_ID);
        requestMap.put("education", List.of(deleteItem));
        Map<String, Object> request = Map.of(Constants.REQUEST, requestMap);

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[] { "education" });
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), anyMap(), any(), any()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(new ArrayList<>(List.of(existingItem)));
        when(cassandraOperation.insertRecord(any(), any(), any()))
                .thenReturn(mockResponse);

        ApiResponse response = profileService.deleteExtendedProfile(request, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
    }

    @Test
    public void testReadFullExtendedProfile_fromCache_success() throws Exception {
        String contextType = "education";
        List<Map<String, Object>> data = List.of(Map.of("degree", "MSc"));

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(anyString())).thenReturn("[{'degree':'MSc'}]");
        when(projectUtil.parseListOfMap(anyString())).thenReturn(data);

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, contextType, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESPONSE));
    }

    @ParameterizedTest
    @MethodSource("contextProvider")
    public void testSaveExtendedProfile_shouldSortByDateField(TestContext testContext) throws Exception {
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put(Constants.USER_ID_RQST, USER_ID);
        requestMap.put(testContext.contextKey, testContext.testData);

        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestMap);

        ApiResponse mockResponse = new ApiResponse();
        mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[]{ testContext.contextKey });
        when(serverProperties.getEducationalQualificationMandatoryFields()).thenReturn("dummyField");
        when(serverProperties.getAchievementsMandatoryFields()).thenReturn("dummyField");
        when(serverProperties.getServiceHistoryMandatoryFields()).thenReturn("dummyField");

        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), anyMap(), any(), any()))
                .thenReturn(new ArrayList<>());

        when(cassandraOperation.insertRecord(any(), any(), any())).thenReturn(mockResponse);

        ApiResponse response = profileService.saveExtendedProfile(request, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESULT));
    }

    @Test
    void testListCompetencies_invalidToken() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(null);

        ApiResponse response = profileService.listCompetencies(USER_ID, TOKEN);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getResponseCode());
        assertEquals("Invalid or missing access token", response.getParams().getErrMsg());
    }

    @Test
    void testListCompetencies_noCoursesCompleted() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        lenient().when(cacheService.getCache(CACHE_KEY)).thenReturn(null);

        Map<String, Object> dbRecord = Map.of(
                Constants.ACTIVE, true,
                Constants.STATUS, 1,
                Constants.COURSE_ID, "course1"
        );
        when(cassandraOperation.getAllRecordsByPrimaryKey(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));

        ApiResponse response = profileService.listCompetencies(USER_ID, TOKEN);

        assertEquals(HttpStatus.NO_CONTENT, response.getResponseCode());
        assertEquals("No competencies found for user.", response.getParams().getErrMsg());
    }



    @Test
    void testGetCourseMetadataBatched_emptyOrInvalidJson() throws IOException {
        List<String> courseIds = List.of("c1");
        when(cacheService.getCourseMetadataAsJsonString(courseIds)).thenReturn(Map.of("c1", "{}"));
        when(projectUtil.parseMap("{}")).thenReturn(null);

        Map<String, Map<String, Object>> result = profileService.getCourseMetadataBatched(courseIds, 10, List.of("a"));
        assertTrue(result.isEmpty());
    }


    @Test
    void testListCompetencies_cacheHit() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        lenient().when(cacheService.getCache(CACHE_KEY)).thenReturn("{\"dummy\":1}");

        ApiResponse response = profileService.listCompetencies(USER_ID, TOKEN);

        assertEquals(HttpStatus.NO_CONTENT, response.getResponseCode());
        assertEquals("No competencies found for user.", response.getParams().getErrMsg());
    }

    @Test
    void testListCompetencies_exception() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(CACHE_KEY)).thenThrow(new RuntimeException("Redis down"));

        ApiResponse response = profileService.listCompetencies(USER_ID, TOKEN);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Internal server error while fetching competencies", response.getParams().getErrMsg());
    }

    @Test
    void testExtendedProfile_invalidToken() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(null);

        ApiResponse response = profileService.getExtendedProfileSummary(USER_ID, TOKEN);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Invalid UserId in the request", response.getParams().getErrMsg());
    }

    @Test
    void testExtendedProfile_cacheHit() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        String cachedJson = "{\"contextA\":{\"count\":3,\"data\":[{\"a\":1},{\"b\":2},{\"c\":3}]}}";

        String redisKey = "user:extendedProfile:all:user-123"; // Correct key
        when(cacheService.getCache(redisKey)).thenReturn(cachedJson);

        Map<String, Object> fullMap = Map.of("contextA", Map.of(
                "count", 3,
                "data", List.of(
                        Map.of("a", 1),
                        Map.of("b", 2),
                        Map.of("c", 3)
                )
        ));
        when(objectMapper.readValue(cachedJson, Map.class)).thenReturn(fullMap);

        ApiResponse response = profileService.getExtendedProfileSummary(USER_ID, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESPONSE));
    }

    @Test
    void testExtendedProfile_cacheWriteFails() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(CACHE_KEY)).thenReturn(null);
        when(serverProperties.getContextType()).thenReturn(CONTEXT_TYPE);

        String contextJson = "[{\"a\":1}]";
        List<Map<String, Object>> records = List.of(Map.of(Constants.CONTEXT_DATA, contextJson));
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(records);

        when(projectUtil.parseListOfMap(contextJson)).thenReturn(List.of(Map.of("a", 1)));
//        when(objectMapper.writeValueAsString(any())).thenThrow(new IOException("fail"));

        ApiResponse response = profileService.getExtendedProfileSummary(USER_ID, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESPONSE));
    }

    @Test
    void testExtendedProfile_emptyData() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(CACHE_KEY)).thenReturn(null);
        when(serverProperties.getContextType()).thenReturn(CONTEXT_TYPE);
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        ApiResponse response = profileService.getExtendedProfileSummary(USER_ID, TOKEN);

        assertEquals(HttpStatus.NO_CONTENT, response.getResponseCode());
        assertEquals("No data found for user.", response.getParams().getErrMsg());
    }

    @Test
    void testExtendedProfile_cacheError_thenCassandraData() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(CACHE_KEY)).thenThrow(new RuntimeException("Simulated"));

        when(serverProperties.getContextType()).thenReturn(CONTEXT_TYPE);

        String contextJson = "[{\"x\":\"1\"},{\"y\":\"2\"}]";
        List<Map<String, Object>> dbRecords = List.of(Map.of(Constants.CONTEXT_DATA, contextJson));
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(dbRecords);

        List<Map<String, Object>> parsed = List.of(Map.of("x", "1"), Map.of("y", "2"));
        when(projectUtil.parseListOfMap(contextJson)).thenReturn(parsed);

        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ApiResponse response = profileService.getExtendedProfileSummary(USER_ID, TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESPONSE));
    }

    @Test
    void testInvalidToken() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(null);

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, Arrays.toString(CONTEXT_TYPE), TOKEN);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testCacheHit() throws Exception {
        String cachedJson = "[{\"data\": \"test\"}]";
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(REDIS_KEY)).thenReturn(cachedJson);

        List<Map<String, Object>> contextList = List.of(Map.of("data", "test"));
        when(projectUtil.parseListOfMap(cachedJson)).thenReturn(contextList);

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, Arrays.toString(CONTEXT_TYPE), TOKEN);

        assertEquals(HttpStatus.NO_CONTENT, response.getResponseCode());
        assertEquals("No data found for user.", response.getParams().getErrMsg());
    }

    @Test
    void testCacheMiss_thenFetchFromCassandra_success() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(REDIS_KEY)).thenReturn(null);

        String json = "[{\"data\": \"test\"}]";
        Map<String, Object> cassandraRow = Map.of(Constants.CONTEXT_DATA, json);
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(List.of(cassandraRow));

        List<Map<String, Object>> parsedList = List.of(Map.of("data", "test"));
        when(projectUtil.parseListOfMap(json)).thenReturn(parsedList);
        when(objectMapper.writeValueAsString(parsedList)).thenReturn(json);

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, Arrays.toString(CONTEXT_TYPE), TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(parsedList.size(), ((Map<?, ?>) response.getResult().get(Constants.RESPONSE)).get(Constants.COUNT));
    }

    @Test
    void testCacheMiss_thenFetchFromCassandra_emptyResult() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(REDIS_KEY)).thenReturn(null);
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, Arrays.toString(CONTEXT_TYPE), TOKEN);

        assertEquals(HttpStatus.NO_CONTENT, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testParseListOfMapException() throws Exception {
        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(REDIS_KEY)).thenReturn("[invalid_json]");
        when(projectUtil.parseListOfMap("[invalid_json]")).thenThrow(new IOException("fail"));

        // fallback to Cassandra
        String json = "[{\"data\": \"test\"}]";
        Map<String, Object> cassandraRow = Map.of(Constants.CONTEXT_DATA, json);
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(List.of(cassandraRow));
        when(projectUtil.parseListOfMap(json)).thenReturn(List.of(Map.of("data", "test")));
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("fail"));

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, Arrays.toString(CONTEXT_TYPE), TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testLocationDetailsBranch() {
        String contextType = Constants.LOCATION_DETAILS;
        String json = "[{\"location\": \"India\"}]";

        when(accessTokenValidator.fetchUserIdFromAccessToken(TOKEN)).thenReturn(USER_ID);
        when(cacheService.getCache(any())).thenReturn(null);
        when(cassandraOperation.getRecordsByPropertiesByKey(any(), any(), any(), any(), any()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, json)));
        try {
            when(projectUtil.parseListOfMap(json)).thenReturn(List.of(Map.of("location", "India")));
            when(objectMapper.writeValueAsString(any())).thenReturn(json);
        } catch (Exception e) {
            fail("Should not throw exception");
        }

        ApiResponse response = profileService.readFullExtendedProfile(USER_ID, contextType, TOKEN);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertTrue(response.getResult().get(Constants.RESPONSE) instanceof Map);
    }

    @Test
    void testGetBasicProfile_withInvalidToken() {
        String userId = "user-123";
        String token = "invalid-token";

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn(null);

        ApiResponse response = profileService.getBasicProfile(userId, token);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getResponseCode());
        assertEquals("Invalid or missing access token", response.getParams().getErrMsg());
    }

    // Use reflection to test private methods:
    @Test
    void testBuildCacheKey() throws Exception {
        Method method = ProfileServiceImpl.class.getDeclaredMethod("buildCacheKey", String.class, String.class, String.class);
        method.setAccessible(true);
        String key = (String) method.invoke(profileService, "user", "basicProfile", "u123");
        assertEquals("user:basicProfile:u123", key);
    }

    @Test
    void testSaveExtendedProfile_invalidUserId() {
        String userToken = "token123";
        String userId = "user123";

        Map<String, Object> requestData = new HashMap<>();
        requestData.put("userId", userId);

        Map<String, Object> request = new HashMap<>();
        request.put("request", requestData);

        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn("wrongUser");

        ApiResponse response = profileService.saveExtendedProfile(request, userToken);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Invalid UserId in the request", response.getParams().getErrMsg());
    }


    @Test
    void testSaveExtendedProfile_invalidUserId1() {
        Map<String, Object> req = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put(Constants.USER_ID_RQST, USER_ID);
        req.put(Constants.REQUEST, inner);

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn("wrong-user");

        ApiResponse response = profileService.saveExtendedProfile(req, "token");

        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Invalid UserId in the request", response.getParams().getErrMsg());
    }

    @Test
    void testSaveExtendedProfile_invalidContextType() {
        Map<String, Object> req = new HashMap<>();
        Map<String, Object> inner = new HashMap<>();
        inner.put(Constants.USER_ID_RQST, USER_ID);
        inner.put("invalidContext", new ArrayList<>());
        req.put(Constants.REQUEST, inner);

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[] {"validContext"});

        ApiResponse response = profileService.saveExtendedProfile(req, "token");

        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("Invalid context type"));
    }

    @Test
    void testSaveExtendedProfile_validationFails() {
        Map<String, Object> req = Map.of(Constants.REQUEST, Map.of(Constants.USER_ID_RQST, USER_ID));

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[] {});

        ApiResponse response = profileService.saveExtendedProfile(req, "token");

        assertEquals(Constants.OK, response.getResponseCode().getReasonPhrase());
    }

    @Test
    void testSaveExtendedProfile_nullIncomingList() {
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, USER_ID);
        requestData.put("contextA", null);

        Map<String, Object> req = Map.of(Constants.REQUEST, requestData);

        when(accessTokenValidator.fetchUserIdFromAccessToken(anyString())).thenReturn(USER_ID);
        when(serverProperties.getContextType()).thenReturn(new String[] {"contextA"});

        ApiResponse response = profileService.saveExtendedProfile(req, "token");

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    public void testSaveExtendedProfile_ValidationFailure_ReturnsBadRequest() {
        String userId = "user-123";
        String userToken = "valid-token";
        Map<String, Object> educationItem = new HashMap<>();
        educationItem.put("institute", "Test University");
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(Constants.EDUCATIONAL_QUALIFICATIONS, List.of(educationItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{Constants.EDUCATIONAL_QUALIFICATIONS});
        when(serverProperties.getEducationalQualificationMandatoryFields()).thenReturn("degree,institute");
        when(serverProperties.getAchievementsMandatoryFields()).thenReturn("");
        when(serverProperties.getServiceHistoryMandatoryFields()).thenReturn("");
        ApiResponse response = profileService.saveExtendedProfile(request, userToken);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertNotNull(response.getParams().getErrMsg());
        assertTrue(response.getParams().getErrMsg().contains("degree is mandatory"));
    }

    @Test
    public void testSaveExtendedProfile_EmptyIncomingList_SkipsProcessingAndReturnsSuccess() {
        String userId = "user-123";
        String userToken = "valid-token";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(Constants.EDUCATIONAL_QUALIFICATIONS, Collections.emptyList());
        Map<String, Object> validItem = new HashMap<>();
        validItem.put("someField", "someValue");
        requestData.put("otherContextType", List.of(validItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{
                Constants.EDUCATIONAL_QUALIFICATIONS, "otherContextType"
        });
        when(serverProperties.getEducationalQualificationMandatoryFields()).thenReturn("degree,institute");
        when(serverProperties.getAchievementsMandatoryFields()).thenReturn("");
        when(serverProperties.getServiceHistoryMandatoryFields()).thenReturn("");
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(new ArrayList<>());
        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.put(Constants.RESPONSE, Constants.SUCCESS);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap())).thenReturn(mockInsertResponse);
        ApiResponse response = profileService.saveExtendedProfile(request, userToken);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(cassandraOperation, never()).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.containsKey(Constants.CONTEXT_TYPE) &&
                        map.get(Constants.CONTEXT_TYPE).equals(Constants.EDUCATIONAL_QUALIFICATIONS))
        );
        verify(cassandraOperation, atLeastOnce()).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.containsKey(Constants.CONTEXT_TYPE) &&
                        map.get(Constants.CONTEXT_TYPE).equals("otherContextType"))
        );
    }

    @Test
    public void testSaveExtendedProfile_SaveContextDataFails_ReturnsError() throws Exception {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = Constants.EDUCATIONAL_QUALIFICATIONS;
        Map<String, Object> educationItem = new HashMap<>();
        educationItem.put("degree", "Masters");
        educationItem.put("institute", "Test University");
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType, List.of(educationItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(serverProperties.getEducationalQualificationMandatoryFields()).thenReturn("degree,institute");
        when(serverProperties.getAchievementsMandatoryFields()).thenReturn("");
        when(serverProperties.getServiceHistoryMandatoryFields()).thenReturn("");
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(new ArrayList<>());
        ApiResponse mockFailureResponse = new ApiResponse();
        mockFailureResponse.put(Constants.RESPONSE, Constants.FAILED);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap())).thenReturn(mockFailureResponse);
        ApiResponse response = profileService.saveExtendedProfile(request, userToken);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Failed to save data for contextType: " + contextType, response.getParams().getErrMsg());
        verify(cassandraOperation).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.get(Constants.CONTEXT_TYPE).equals(contextType))
        );
    }

    @Test
    public void testSaveExtendedProfile_UserIdMismatchWithToken_ReturnsBadRequest() {
        String tokenUserId = "token-user-123";  // User ID from token
        String requestUserId = "request-user-456";  // Different user ID in request
        String userToken = "some-token";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, requestUserId);
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(tokenUserId);
        ApiResponse response = profileService.saveExtendedProfile(request, userToken);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Invalid UserId in the request", response.getParams().getErrMsg());
        verify(accessTokenValidator).fetchUserIdFromAccessToken(userToken);
        verifyNoMoreInteractions(cassandraOperation, cacheService);
    }

    @Test
    public void testSaveExtendedProfile_NullOrEmptyList_SkipsProcessing() {
        String userId = "user-123";
        String userToken = "valid-token";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(Constants.EDUCATIONAL_QUALIFICATIONS, Collections.emptyList());  // Empty list
        requestData.put(Constants.SERVICE_HISTORY, null);  // Null list
        Map<String, Object> achievementItem = new HashMap<>();
        achievementItem.put("title", "Achievement 1");
        achievementItem.put("issuer", "Issuer 1");
        requestData.put(Constants.ACHIEVEMENTS, List.of(achievementItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{
                Constants.EDUCATIONAL_QUALIFICATIONS,
                Constants.SERVICE_HISTORY,
                Constants.ACHIEVEMENTS
        });
        when(serverProperties.getEducationalQualificationMandatoryFields()).thenReturn("");
        when(serverProperties.getAchievementsMandatoryFields()).thenReturn("title,issuer");
        when(serverProperties.getServiceHistoryMandatoryFields()).thenReturn("");
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(new ArrayList<>());
        ApiResponse mockResponse = new ApiResponse();
        mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap())).thenReturn(mockResponse);
        ApiResponse response = profileService.saveExtendedProfile(request, userToken);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(cassandraOperation, never()).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.get(Constants.CONTEXT_TYPE).equals(Constants.EDUCATIONAL_QUALIFICATIONS))
        );
        verify(cassandraOperation, never()).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.get(Constants.CONTEXT_TYPE).equals(Constants.SERVICE_HISTORY))
        );
        verify(cassandraOperation).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.get(Constants.CONTEXT_TYPE).equals(Constants.ACHIEVEMENTS))
        );
    }

    @Test
    public void testUpdateExtendedProfile_FiltersOutItemsWithoutUuid() throws IOException {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        String uuid1 = "uuid-1";
        String uuid2 = "uuid-2";
        List<Map<String, Object>> existingData = new ArrayList<>();
        Map<String, Object> item1 = new HashMap<>();
        item1.put(Constants.UUID, uuid1);
        item1.put("degree", "Bachelor's");
        existingData.add(item1);
        Map<String, Object> item2 = new HashMap<>();
        item2.put(Constants.UUID, uuid2);
        item2.put("degree", "Master's");
        existingData.add(item2);
        Map<String, Object> item3 = new HashMap<>();
        item3.put("degree", "PhD");
        existingData.add(item3);
        Map<String, Object> update = new HashMap<>();
        update.put(Constants.UUID, uuid1);
        update.put("degree", "Updated Bachelor's");
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType, List.of(update));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(existingData);
        String updatedJsonData = "[{\"uuid\":\"uuid-1\",\"degree\":\"Updated Bachelor's\"},{\"uuid\":\"uuid-2\",\"degree\":\"Master's\"}]";
        when(objectMapper.writeValueAsString(any())).thenReturn(updatedJsonData);
        ApiResponse mockResponse = new ApiResponse();
        mockResponse.put(Constants.RESPONSE, Constants.SUCCESS);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap())).thenReturn(mockResponse);
        ApiResponse response = profileService.updateExtendedProfile(request, userToken);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        ArgumentCaptor<Map<String, Object>> insertCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cassandraOperation).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                insertCaptor.capture());
        Map<String, Object> savedData = insertCaptor.getValue();
        assertEquals(updatedJsonData, savedData.get(Constants.CONTEXT_DATA));
        String contextData = (String) savedData.get(Constants.CONTEXT_DATA);
        assertTrue(contextData.contains(uuid1));
        assertTrue(contextData.contains(uuid2));
        assertTrue(contextData.contains("Updated Bachelor's"));
        assertFalse(contextData.contains("PhD"));
    }

    @Test
    public void testUpdateExtendedProfile_InvalidUuid_ReturnsBadRequest() throws IOException {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        String nonExistentUuid = "uuid-does-not-exist";
        List<Map<String, Object>> existingData = new ArrayList<>();
        existingData.add(Map.of(
                Constants.UUID, "existing-uuid-1",
                "degree", "Bachelor's"
        ));
        existingData.add(Map.of(
                Constants.UUID, "existing-uuid-2",
                "degree", "Master's"
        ));
        Map<String, Object> updateItem = new HashMap<>();
        updateItem.put(Constants.UUID, nonExistentUuid);
        updateItem.put("degree", "PhD");
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType, List.of(updateItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(existingData);
        ApiResponse response = profileService.updateExtendedProfile(request, userToken);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Invalid or missing UUID in incoming data.", response.getParams().getErrMsg());
        verify(cassandraOperation, never()).insertRecord(anyString(), anyString(), anyMap());
    }

    @Test
    public void testUpdateExtendedProfile_SaveContextDataFails_ReturnsError() throws Exception {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        String uuid = "existing-uuid-1";
        List<Map<String, Object>> existingData = new ArrayList<>();
        Map<String, Object> existingItem = new HashMap<>();
        existingItem.put(Constants.UUID, uuid);
        existingItem.put("degree", "Bachelor's");
        existingData.add(existingItem);
        Map<String, Object> updateItem = new HashMap<>();
        updateItem.put(Constants.UUID, uuid);
        updateItem.put("degree", "Updated Degree");
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType, List.of(updateItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), isNull(), isNull()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(existingData);
        ApiResponse failureResponse = new ApiResponse();
        failureResponse.put(Constants.RESPONSE, Constants.FAILED);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap())).thenReturn(failureResponse);
        ApiResponse response = profileService.updateExtendedProfile(request, userToken);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Failed to update data for contextType: " + contextType, response.getParams().getErrMsg());
        verify(cassandraOperation).insertRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_EXTENDED_PROFILE),
                argThat(map -> map.get(Constants.CONTEXT_TYPE).equals(contextType))
        );
    }

    @Test
    public void testUpdateExtendedProfile_UserIdMismatch_ReturnsBadRequest() {
        String requestUserId = "user-123";
        String tokenUserId = "different-user-456";
        String userToken = "token-for-different-user";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, requestUserId);
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(tokenUserId);
        ApiResponse response = profileService.updateExtendedProfile(request, userToken);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Invalid UserId in the request", response.getParams().getErrMsg());
        verify(serverProperties, never()).getContextType();
        verify(cassandraOperation, never()).getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any());
    }


    @Test
    public void testUpdateExtendedProfile_EmptyIncomingList_SkipsProcessing() {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType1 = "education";
        String contextType2 = "workExperience";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType1, Collections.emptyList());  // Empty list
        requestData.put(contextType2, null);  // Null list
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType1, contextType2});
        ApiResponse response = profileService.updateExtendedProfile(request, userToken);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        verify(cassandraOperation, never()).getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any());
        verify(cassandraOperation, never()).insertRecord(anyString(), anyString(), anyMap());
    }

    @Test
    public void testUpdateExtendedProfile_NullUuid_ReturnsBadRequest() {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        Map<String, Object> updateWithNullUuid = new HashMap<>();
        updateWithNullUuid.put("degree", "Updated Bachelor's");
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType, List.of(updateWithNullUuid));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(new ArrayList<>());
        ApiResponse response = profileService.updateExtendedProfile(request, userToken);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Invalid or missing UUID in incoming data.", response.getParams().getErrMsg());
        verify(cassandraOperation).getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any());
        verify(cassandraOperation, never()).insertRecord(anyString(), anyString(), anyMap());
    }

    @Test
    public void testDeleteExtendedProfile_SaveContextDataFails_ReturnsError() throws Exception {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        String uuid = UUID.randomUUID().toString();
        Map<String, Object> deleteItem = Map.of(Constants.UUID, uuid);
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType, List.of(deleteItem));
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[]")));
        when(projectUtil.parseListOfMap(anyString()))
                .thenReturn(new ArrayList<>(List.of(new HashMap<>(Map.of(Constants.UUID, uuid)))));
        ApiResponse failedResponse = new ApiResponse();
        failedResponse.put(Constants.RESPONSE, Constants.FAILED);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap())).thenReturn(failedResponse);
        ApiResponse response = profileService.deleteExtendedProfile(request, userToken);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Failed to delete data for contextType: " + contextType, response.getParams().getErrMsg());
    }

    @Test
    public void testGetExtendedProfileSummary_CachePutThrowsException_LogsWarning() throws Exception {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        List<Map<String, Object>> contextData = List.of(Map.of("field", "value"));
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType});
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(List.of(Map.of(Constants.CONTEXT_DATA, "[{\"field\":\"value\"}]")));
        when(projectUtil.parseListOfMap(anyString())).thenReturn(
                new ArrayList<>(List.of(new HashMap<>(Map.of("field", "value"))))
        );
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        doThrow(new RuntimeException("Cache error")).when(cacheService).putCache(anyString(), anyString());
        ApiResponse response = profileService.getExtendedProfileSummary(userId, userToken);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.get(Constants.RESPONSE));
        verify(cacheService).putCache(anyString(), isNull());
    }

    @Test
    void testDeleteExtendedProfile_UserIdMismatch_ReturnsBadRequest() {
        String requestUserId = "user-123";
        String tokenUserId = "different-user-456";
        String userToken = "token-for-different-user";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, requestUserId);
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(tokenUserId);
        ApiResponse response = profileService.deleteExtendedProfile(request, userToken);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Invalid UserId in the request", response.getParams().getErrMsg());
    }

    @Test
    void testDeleteExtendedProfile_ToDeleteListNullOrEmpty_SkipsProcessing() {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType1 = "education";
        String contextType2 = "workExperience";
        Map<String, Object> requestData = new HashMap<>();
        requestData.put(Constants.USER_ID_RQST, userId);
        requestData.put(contextType1, null); // null list
        requestData.put(contextType2, Collections.emptyList()); // empty list
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.REQUEST, requestData);
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(serverProperties.getContextType()).thenReturn(new String[]{contextType1, contextType2});
        ApiResponse response = profileService.deleteExtendedProfile(request, userToken);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.get(Constants.RESPONSE));
        verify(cassandraOperation, never()).getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any());
        verify(cassandraOperation, never()).insertRecord(anyString(), anyString(), anyMap());
        verify(cacheService, never()).putCache(anyString(), any());
    }

    @Test
    void testReadFullExtendedProfile_NoContextData_ReturnsNoContent() throws Exception {
        String userId = "user-123";
        String userToken = "valid-token";
        String contextType = "education";
        String redisKey = "user:extendedProfile:" + contextType + ":" + userId;
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(cacheService.getCache(redisKey)).thenReturn(null);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(null); // or Collections.emptyList()
        lenient().when(projectUtil.parseListOfMap(anyString())).thenReturn(Collections.emptyList());
        ApiResponse response = profileService.readFullExtendedProfile(userId, contextType, userToken);
        assertEquals(HttpStatus.NO_CONTENT, response.getResponseCode());
        assertEquals("No data found for user.", response.getParams().getErrMsg());
    }


    @Test
    void returnsValidCount() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        RequestHandlerServiceImpl requestHandlerService = mock(RequestHandlerServiceImpl.class);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "requestHandlerService", requestHandlerService);
        when(serverConfig.getCommunityBaseUrl()).thenReturn("http://base/");
        when(serverConfig.getCommunityPostCountApiUrl()).thenReturn("api/count/");
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.POSTCOUNT, 5);
        Map<String, Object> response = new HashMap<>();
        response.put(Constants.RESULT, result);
        when(requestHandlerService.fetchUsingGetWithHeadersProfile(anyString(), isNull()))
                .thenReturn(response);
        int count = ReflectionTestUtils.invokeMethod(service, "fetchPostCountFromApi", "user-1");
        assertEquals(5, count);
    }

    @Test
    void returnsZeroOnNullResponse() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        RequestHandlerServiceImpl requestHandlerService = mock(RequestHandlerServiceImpl.class);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "requestHandlerService", requestHandlerService);
        when(serverConfig.getCommunityBaseUrl()).thenReturn("http://base/");
        when(serverConfig.getCommunityPostCountApiUrl()).thenReturn("api/count/");
        when(requestHandlerService.fetchUsingGetWithHeadersProfile(anyString(), isNull()))
                .thenReturn(null);
        int count = ReflectionTestUtils.invokeMethod(service, "fetchPostCountFromApi", "user-2");
        assertEquals(0, count);
    }

    @Test
    void returnsZeroOnMissingResult() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        RequestHandlerServiceImpl requestHandlerService = mock(RequestHandlerServiceImpl.class);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "requestHandlerService", requestHandlerService);
        when(serverConfig.getCommunityBaseUrl()).thenReturn("http://base/");
        when(serverConfig.getCommunityPostCountApiUrl()).thenReturn("api/count/");
        Map<String, Object> response = new HashMap<>();
        when(requestHandlerService.fetchUsingGetWithHeadersProfile(anyString(), isNull()))
                .thenReturn(response);

        int count = ReflectionTestUtils.invokeMethod(service, "fetchPostCountFromApi", "user-3");
        assertEquals(0, count);
    }

    @Test
    void returnsZeroOnNonIntegerPostCount() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        RequestHandlerServiceImpl requestHandlerService = mock(RequestHandlerServiceImpl.class);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "requestHandlerService", requestHandlerService);
        when(serverConfig.getCommunityBaseUrl()).thenReturn("http://base/");
        when(serverConfig.getCommunityPostCountApiUrl()).thenReturn("api/count/");
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.POSTCOUNT, "not-an-int");
        Map<String, Object> response = new HashMap<>();
        response.put(Constants.RESULT, result);
        when(requestHandlerService.fetchUsingGetWithHeadersProfile(anyString(), isNull()))
                .thenReturn(response);
        int count = ReflectionTestUtils.invokeMethod(service, "fetchPostCountFromApi", "user-4");
        assertEquals(0, count);
    }

    @Test
    void returnsZeroOnException() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        RequestHandlerServiceImpl requestHandlerService = mock(RequestHandlerServiceImpl.class);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "requestHandlerService", requestHandlerService);
        when(serverConfig.getCommunityBaseUrl()).thenReturn("http://base/");
        when(serverConfig.getCommunityPostCountApiUrl()).thenReturn("api/count/");
        when(requestHandlerService.fetchUsingGetWithHeadersProfile(anyString(), isNull()))
                .thenThrow(new RuntimeException("API error"));
        int count = ReflectionTestUtils.invokeMethod(service, "fetchPostCountFromApi", "user-5");
        assertEquals(0, count);
    }

    @Test
    void testGetUserPostCount_cacheHit() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        when(cacheService.getCache("user:communityPostCount:user1")).thenReturn("10");
        int count = ReflectionTestUtils.invokeMethod(service, "getUserPostCount", "user1");
        assertEquals(10, count);
        verify(cacheService).getCache("user:communityPostCount:user1");
        verifyNoMoreInteractions(cacheService);
    }

    @Test
    void testGetUserPostCount_cacheValueNotInteger_returnsZero() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        when(cacheService.getCache("user:communityPostCount:user3")).thenReturn("not-a-number");
        int count = ReflectionTestUtils.invokeMethod(service, "getUserPostCount", "user3");
        assertEquals(0, count);
    }

    @Test
    void testGetUserPostCount_exception_returnsZero() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        when(cacheService.getCache("user:communityPostCount:user4")).thenThrow(new RuntimeException("Redis error"));
        int count = ReflectionTestUtils.invokeMethod(service, "getUserPostCount", "user4");
        assertEquals(0, count);
    }

    @Test
    void sanitizeProfile_removesPersonalDetails_whenPresent() {
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put(Constants.PERSONAL_DETAILS, Map.of("a", "b"));
        Map<String, Object> profile = new HashMap<>();
        profile.put(Constants.PROFILE_DETAILS, detailsMap);
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.invokeMethod(service, "sanitizeProfile", profile);
        assertFalse(detailsMap.containsKey(Constants.PERSONAL_DETAILS));
    }

    @Test
    void sanitizeProfile_doesNothing_whenPersonalDetailsNotPresent() {
        Map<String, Object> detailsMap = new HashMap<>();
        detailsMap.put("other", "value");
        Map<String, Object> profile = new HashMap<>();
        profile.put(Constants.PROFILE_DETAILS, detailsMap);
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.invokeMethod(service, "sanitizeProfile", profile);
        assertTrue(detailsMap.containsKey("other"));
    }

    @Test
    void sanitizeProfile_doesNothing_whenProfileDetailsIsNotMap() {
        Map<String, Object> profile = new HashMap<>();
        profile.put(Constants.PROFILE_DETAILS, "notAMap");
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.invokeMethod(service, "sanitizeProfile", profile);
    }

    @Test
    void sanitizeProfile_doesNothing_whenProfileDetailsIsNull() {
        Map<String, Object> profile = new HashMap<>();
        profile.put(Constants.PROFILE_DETAILS, null);
        ProfileServiceImpl service = new ProfileServiceImpl();
        ReflectionTestUtils.invokeMethod(service, "sanitizeProfile", profile);
    }


    @Test
    void fetchFromDatabase_returnsNull_whenNoRecords() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(null);
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "fetchFromDatabase", "user-1");
        assertNull(result);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(Collections.emptyList());
        result = ReflectionTestUtils.invokeMethod(service, "fetchFromDatabase", "user-1");
        assertNull(result);
    }

    @Test
    void fetchFromDatabase_returnsRecordWithParsedProfileDetails_whenValidJson() throws Exception {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ProjectUtil projectUtil = mock(ProjectUtil.class);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "projectUtil", projectUtil);
        Map<String, Object> record = new HashMap<>();
        record.put(Constants.PROFILE_DETAILS, "{\"email\":\"test@example.com\"}");
        List<Map<String, Object>> records = List.of(record);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(records);
        Map<String, Object> parsed = Map.of("email", "test@example.com");
        when(projectUtil.parseMap("{\"email\":\"test@example.com\"}")).thenReturn(parsed);
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "fetchFromDatabase", "user-2");
        assertNotNull(result);
        assertEquals(parsed, result.get(Constants.PROFILE_DETAILS));
    }

    @Test
    void fetchFromDatabase_removesProfileDetails_whenJsonInvalid() throws Exception {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ProjectUtil projectUtil = mock(ProjectUtil.class);
        Logger logger = mock(Logger.class);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "projectUtil", projectUtil);
        Map<String, Object> record = new HashMap<>();
        record.put(Constants.PROFILE_DETAILS, "{invalid_json}");
        List<Map<String, Object>> records = List.of(record);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(records);
        when(projectUtil.parseMap("{invalid_json}")).thenThrow(new IOException("fail"));
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "fetchFromDatabase", "user-3");
        assertNotNull(result);
        assertFalse(result.containsKey(Constants.PROFILE_DETAILS));
    }

    @Test
    void fetchFromDatabase_leavesProfileDetailsNull_whenProfileDetailsIsNull() throws Exception {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ProjectUtil projectUtil = mock(ProjectUtil.class);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        ReflectionTestUtils.setField(service, "projectUtil", projectUtil);
        Map<String, Object> record = new HashMap<>();
        record.put(Constants.PROFILE_DETAILS, null);
        List<Map<String, Object>> records = List.of(record);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), any(), any()))
                .thenReturn(records);
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(service, "fetchFromDatabase", "user-4");
        assertNotNull(result);
        assertNull(result.get(Constants.PROFILE_DETAILS));
    }

    @Test
    void validateFields_returnsEmptyString_whenAllMandatoryFieldsPresent() {
        Map<String, Object> data = Map.of("degree", "MSc", "institute", "Test University");
        String mandatoryFields = "degree,institute";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, false);
        assertEquals("", result);
    }

    @Test
    void validateFields_returnsErrorMessage_whenMandatoryFieldMissing() {
        Map<String, Object> data = Map.of("degree", "MSc");
        String mandatoryFields = "degree,institute";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, false);
        assertTrue(result.contains("institute is mandatory"));
    }

    @Test
    void validateFields_skipsEndDate_whenCurrentlyWorkingIsTrueAndAllowSkipEndDate() {
        Map<String, Object> data = new HashMap<>();
        data.put("degree", "MSc");
        data.put("endDate", "");
        data.put("currentlyWorking", "true");
        String mandatoryFields = "degree,endDate";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, true);
        assertEquals("", result);
    }

    @Test
    void validateFields_requiresEndDate_whenCurrentlyWorkingIsFalse() {
        Map<String, Object> data = new HashMap<>();
        data.put("degree", "MSc");
        data.put("endDate", "");
        data.put("currentlyWorking", "false");
        String mandatoryFields = "degree,endDate";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, true);
        assertTrue(result.contains("endDate is mandatory"));
    }

    @Test
    void validateFields_handlesBlankMandatoryFields() {
        Map<String, Object> data = Map.of("degree", "MSc");
        String mandatoryFields = "";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, false);
        assertEquals(" is mandatory. ", result);
    }

    @Test
    void validateFields_handlesNullValues() {
        Map<String, Object> data = new HashMap<>();
        data.put("degree", null);
        String mandatoryFields = "degree";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, false);
        assertTrue(result.contains("degree is mandatory"));
    }

    @Test
    void validateFields_handlesMultipleMissingFields() {
        Map<String, Object> data = new HashMap<>();
        String mandatoryFields = "degree,institute";
        ProfileServiceImpl service = new ProfileServiceImpl();
        String result = ReflectionTestUtils.invokeMethod(service, "validateFields", data, mandatoryFields, false);
        assertTrue(result.contains("degree is mandatory"));
        assertTrue(result.contains("institute is mandatory"));
    }



    @Test
    void getUserKarmaPoints_returnsCachedValue_whenCacheHit() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        String userId = "user-1";
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenReturn("42");
        int points = ReflectionTestUtils.invokeMethod(service, "getUserKarmaPoints", userId);
        assertEquals(42, points);
        verify(cacheService).getCache("user:karmaPoints:" + userId);
        verifyNoInteractions(cassandraOperation);
    }

    @Test
    void getUserKarmaPoints_returnsValueFromDatabase_whenCacheMiss() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        String userId = "user-2";
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenReturn(null);
        Map<String, Object> record = new HashMap<>();
        record.put(Constants.TOTAL_POINTS, 17);
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), anyString(), anyMap(), anyList(), eq(userId)))
                .thenReturn(List.of(record));
        int points = ReflectionTestUtils.invokeMethod(service, "getUserKarmaPoints", userId);
        assertEquals(17, points);
        verify(cacheService).putCache("user:karmaPoints:" + userId, "17");
    }

    @Test
    void getUserKarmaPoints_returnsZero_whenNoRecordsInDatabase() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        String userId = "user-3";
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenReturn(null);
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), anyString(), anyMap(), anyList(), eq(userId)))
                .thenReturn(Collections.emptyList());
        int points = ReflectionTestUtils.invokeMethod(service, "getUserKarmaPoints", userId);
        assertEquals(0, points);
        verify(cacheService).putCache("user:karmaPoints:" + userId, "0");
    }

    @Test
    void getUserKarmaPoints_returnsZero_whenCacheValueIsNotInteger() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        String userId = "user-4";
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenReturn("not-a-number");
        int points = ReflectionTestUtils.invokeMethod(service, "getUserKarmaPoints", userId);
        assertEquals(0, points);
    }

    @Test
    void getUserKarmaPoints_returnsZero_whenExceptionThrown() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        String userId = "user-5";
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenThrow(new RuntimeException("Redis error"));
        int points = ReflectionTestUtils.invokeMethod(service, "getUserKarmaPoints", userId);
        assertEquals(0, points);
    }

    @Test
    void getSortingComparator_returnsServiceHistoryComparator_andSortsByStartDateDescending() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Comparator<Map<String, Object>> comparator = ReflectionTestUtils.invokeMethod(service, "getSortingComparator", Constants.SERVICE_HISTORY);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(Map.of(Constants.START_DATE, "2022-01-01T00:00:00Z"));
        data.add(Map.of(Constants.START_DATE, "2023-01-01T00:00:00Z"));
        data.sort(comparator.reversed());
        assertEquals("2023-01-01T00:00:00Z", data.get(0).get(Constants.START_DATE));
    }

    @Test
    void getSortingComparator_returnsEducationalQualificationsComparator_andSortsByStartYearDescending() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Comparator<Map<String, Object>> comparator = ReflectionTestUtils.invokeMethod(service, "getSortingComparator", Constants.EDUCATIONAL_QUALIFICATIONS);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(Map.of(Constants.START_YEAR, "2018"));
        data.add(Map.of(Constants.START_YEAR, "2020"));
        data.sort(comparator.reversed());
        assertEquals("2020", data.get(0).get(Constants.START_YEAR));
    }

    @Test
    void getSortingComparator_returnsAchievementsComparator_andSortsByIssuedDateDescending() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Comparator<Map<String, Object>> comparator = ReflectionTestUtils.invokeMethod(service, "getSortingComparator", Constants.ACHIVEMENTS);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(Map.of(Constants.ISSUED_DATE, "2021-05-01T00:00:00Z"));
        data.add(Map.of(Constants.ISSUED_DATE, "2022-05-01T00:00:00Z"));
        data.sort(comparator.reversed());
        assertEquals("2022-05-01T00:00:00Z", data.get(0).get(Constants.ISSUED_DATE));
    }

    @Test
    void getSortingComparator_returnsNullForUnknownContextType() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Comparator<Map<String, Object>> comparator = ReflectionTestUtils.invokeMethod(service, "getSortingComparator", "unknownType");
        assertNull(comparator);
    }

    @Test
    void getSortingComparator_handlesMissingFieldsGracefully() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Comparator<Map<String, Object>> comparator = ReflectionTestUtils.invokeMethod(service, "getSortingComparator", Constants.EDUCATIONAL_QUALIFICATIONS);
        List<Map<String, Object>> data = new ArrayList<>();
        data.add(new HashMap<>()); // missing START_YEAR
        data.add(Map.of(Constants.START_YEAR, "2020"));
        assertThrows(NumberFormatException.class, () -> data.sort(comparator));
    }

    @Test
    void sortContextData_sortsListDescending_whenComparatorExists() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> dataList = new ArrayList<>();
        dataList.add(Map.of(Constants.START_DATE, "2022-01-01T00:00:00Z"));
        dataList.add(Map.of(Constants.START_DATE, "2023-01-01T00:00:00Z"));
        ReflectionTestUtils.invokeMethod(service, "sortContextData", dataList, Constants.SERVICE_HISTORY);
        assertEquals("2023-01-01T00:00:00Z", dataList.get(0).get(Constants.START_DATE));
    }

    @Test
    void sortContextData_doesNotSort_whenComparatorIsNull() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> dataList = new ArrayList<>();
        dataList.add(Map.of("field", "A"));
        dataList.add(Map.of("field", "B"));
        List<Map<String, Object>> original = new ArrayList<>(dataList);
        ReflectionTestUtils.invokeMethod(service, "sortContextData", dataList, "unknownType");
        assertEquals(original, dataList);
    }

    @Test
    void sortContextData_handlesEmptyList() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> dataList = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(service, "sortContextData", dataList, Constants.SERVICE_HISTORY);
        assertTrue(dataList.isEmpty());
    }

    @Test
    void sortContextData_throwsException_whenFieldMissing() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> dataList = new ArrayList<>();
        dataList.add(new HashMap<>());
        dataList.add(Map.of(Constants.START_YEAR, "2020"));
        assertThrows(NumberFormatException.class, () ->
                ReflectionTestUtils.invokeMethod(service, "sortContextData", dataList, Constants.EDUCATIONAL_QUALIFICATIONS)
        );
    }

    @Test
    void returnsCachedCertificateCount_whenCacheHit() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        when(serverConfig.getCertificateCountRedisKey()).thenReturn("cert:count");
        when(serverConfig.getDataIndex()).thenReturn(12);
        when(serverConfig.getCacheTtl()).thenReturn(100);
        when(cacheService.hget("cert:count", 12, "user-1", 100)).thenReturn("7");
        int count = ReflectionTestUtils.invokeMethod(service, "getIssuedCertificateCount", "user-1");
        assertEquals(7, count);
        verify(cacheService).hget("cert:count", 12, "user-1", 100);
        verifyNoInteractions(cassandraOperation);
    }

    @Test
    void returnsSumOfCertificatesFromBothSources_whenCacheMiss() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        when(serverConfig.getCertificateCountRedisKey()).thenReturn("cert:count");
        when(serverConfig.getDataIndex()).thenReturn(12);
        when(serverConfig.getCacheTtl()).thenReturn(100);
        when(cacheService.hget("cert:count", 12, "user-2", 100)).thenReturn(null);

        List<Map<String, Object>> courseRecords = List.of(
                Map.of(Constants.ISSUED_CERTIFICATES_KEY, List.of("c1", "c2")),
                Map.of(Constants.ISSUED_CERTIFICATES_KEY, List.of("c3"))
        );
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), eq(Constants.USER_ENROLMENTS), anyMap(), anyList(), eq("user-2")
        )).thenReturn(courseRecords);

        List<Map<String, Object>> eventRecords = List.of(
                Map.of(
                        Constants.STATUS, 2,
                        Constants.PROGRESS_KEY, 100,
                        Constants.ISSUED_CERTIFICATES_KEY, List.of("e1")
                ),
                Map.of(
                        Constants.STATUS, 2,
                        Constants.PROGRESS_KEY, 100,
                        Constants.ISSUED_CERTIFICATES_KEY, List.of("e2", "e3")
                ),
                Map.of(
                        Constants.STATUS, 1,
                        Constants.PROGRESS_KEY, 100,
                        Constants.ISSUED_CERTIFICATES_KEY, List.of("shouldNotCount")
                )
        );
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), eq(Constants.USER_ENTITY_ENROLMENTS), anyMap(), anyList(), eq("user-2")
        )).thenReturn(eventRecords);

        int count = ReflectionTestUtils.invokeMethod(service, "getIssuedCertificateCount", "user-2");
        assertEquals(6, count);
        verify(cacheService).hset("cert:count", 12, "user-2", "6");
    }

    @Test
    void returnsZero_whenNoCertificatesAndCacheMiss() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        when(serverConfig.getCertificateCountRedisKey()).thenReturn("cert:count");
        when(serverConfig.getDataIndex()).thenReturn(12);
        when(serverConfig.getCacheTtl()).thenReturn(100);
        when(cacheService.hget("cert:count", 12, "user-3", 100)).thenReturn(null);
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), eq(Constants.USER_ENROLMENTS), anyMap(), anyList(), eq("user-3")
        )).thenReturn(Collections.emptyList());
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), eq(Constants.USER_ENTITY_ENROLMENTS), anyMap(), anyList(), eq("user-3")
        )).thenReturn(Collections.emptyList());
        int count = ReflectionTestUtils.invokeMethod(service, "getIssuedCertificateCount", "user-3");
        assertEquals(0, count);
        verify(cacheService).hset("cert:count", 12, "user-3", "0");
    }

    @Test
    void returnsZero_whenExceptionIsThrown() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        Logger logger = mock(Logger.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        when(serverConfig.getCertificateCountRedisKey()).thenReturn("cert:count");
        when(serverConfig.getDataIndex()).thenReturn(12);
        when(serverConfig.getCacheTtl()).thenReturn(100);
        when(cacheService.hget(anyString(), anyInt(), anyString(), anyInt())).thenThrow(new RuntimeException("fail"));
        int count = ReflectionTestUtils.invokeMethod(service, "getIssuedCertificateCount", "user-4");
        assertEquals(0, count);
    }

    @Test
    void ignoresNonListIssuedCertificatesAndNulls() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        CacheService cacheService = mock(CacheService.class);
        CassandraOperation cassandraOperation = mock(CassandraOperation.class);
        CbServerProperties serverConfig = mock(CbServerProperties.class);
        ReflectionTestUtils.setField(service, "cacheService", cacheService);
        ReflectionTestUtils.setField(service, "cassandraOperation", cassandraOperation);
        ReflectionTestUtils.setField(service, "serverConfig", serverConfig);
        when(serverConfig.getCertificateCountRedisKey()).thenReturn("cert:count");
        when(serverConfig.getDataIndex()).thenReturn(12);
        when(serverConfig.getCacheTtl()).thenReturn(100);
        when(cacheService.hget("cert:count", 12, "user-5", 100)).thenReturn(null);

        List<Map<String, Object>> courseRecords = List.of(
                new HashMap<String, Object>() {{ put(Constants.ISSUED_CERTIFICATES_KEY, null); }},
                Map.of(Constants.ISSUED_CERTIFICATES_KEY, "notAList")
        );
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), eq(Constants.USER_ENROLMENTS), anyMap(), anyList(), eq("user-5")
        )).thenReturn(courseRecords);

        List<Map<String, Object>> eventRecords = List.of(
                new HashMap<String, Object>() {{
                    put(Constants.STATUS, 2);
                    put(Constants.PROGRESS_KEY, 100);
                    put(Constants.ISSUED_CERTIFICATES_KEY, null);
                }},
                Map.of(Constants.STATUS, 2, Constants.PROGRESS_KEY, 100, Constants.ISSUED_CERTIFICATES_KEY, "notAList")
        );
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), eq(Constants.USER_ENTITY_ENROLMENTS), anyMap(), anyList(), eq("user-5")
        )).thenReturn(eventRecords);

        int count = ReflectionTestUtils.invokeMethod(service, "getIssuedCertificateCount", "user-5");
        assertEquals(0, count);
        verify(cacheService).hset("cert:count", 12, "user-5", "0");
    }

    @Test
    void mergeAndSortByIssuedDateOrTitle_sortsByIssuedDateDescending() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> existingList = new ArrayList<>();
        List<Map<String, Object>> newList = new ArrayList<>();
        existingList.add(new HashMap<>(Map.of(Constants.ISSUED_DATE, "2022-01-01T00:00:00Z", Constants.TITLE, "B")));
        newList.add(new HashMap<>(Map.of(Constants.ISSUED_DATE, "2023-01-01T00:00:00Z", Constants.TITLE, "A")));
        ReflectionTestUtils.invokeMethod(
                service, "mergeAndSortByIssuedDateOrTitle", existingList, newList
        );
        assertEquals("2023-01-01T00:00:00Z", existingList.get(0).get(Constants.ISSUED_DATE));
        assertEquals(0, existingList.get(0).get(Constants.INDEX));
        assertEquals(1, existingList.get(1).get(Constants.INDEX));
    }

    @Test
    void mergeAndSortByIssuedDateOrTitle_sortsByTitleWhenDatesMissing() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> existingList = new ArrayList<>();
        List<Map<String, Object>> newList = new ArrayList<>();
        existingList.add(new HashMap<>(Map.of(Constants.TITLE, "Bravo")));
        newList.add(new HashMap<>(Map.of(Constants.TITLE, "Alpha")));
        ReflectionTestUtils.invokeMethod(
                service, "mergeAndSortByIssuedDateOrTitle", existingList, newList
        );
        assertEquals("Alpha", existingList.get(0).get(Constants.TITLE));
        assertEquals("Bravo", existingList.get(1).get(Constants.TITLE));
    }

    @Test
    void mergeAndSortByIssuedDateOrTitle_handlesNullTitles() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> existingList = new ArrayList<>();
        List<Map<String, Object>> newList = new ArrayList<>();
        existingList.add(new HashMap<>());
        newList.add(new HashMap<>(Map.of(Constants.TITLE, "Alpha")));
        ReflectionTestUtils.invokeMethod(
                service, "mergeAndSortByIssuedDateOrTitle", existingList, newList
        );
        assertEquals("Alpha", existingList.get(0).get(Constants.TITLE));
        assertNull(existingList.get(1).get(Constants.TITLE));
    }

    @Test
    void mergeAndSortByIssuedDateOrTitle_handlesNullLists() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> existingList = new ArrayList<>();
        List<Map<String, Object>> newList = new ArrayList<>();
        ReflectionTestUtils.invokeMethod(
                service, "mergeAndSortByIssuedDateOrTitle", existingList, newList
        );
        assertTrue(existingList.isEmpty());
    }

    @Test
    void mergeAndSortByIssuedDateOrTitle_sortsWhenSomeDatesNull() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        List<Map<String, Object>> existingList = new ArrayList<>();
        List<Map<String, Object>> newList = new ArrayList<>();
        existingList.add(new HashMap<>(Map.of(Constants.TITLE, "Bravo")));
        newList.add(new HashMap<>(Map.of(Constants.ISSUED_DATE, "2023-01-01T00:00:00Z", Constants.TITLE, "Alpha")));
        ReflectionTestUtils.invokeMethod(
                service, "mergeAndSortByIssuedDateOrTitle", existingList, newList
        );
        assertEquals("2023-01-01T00:00:00Z", existingList.get(0).get(Constants.ISSUED_DATE));
        assertEquals("Bravo", existingList.get(1).get(Constants.TITLE));
    }

    @Test
    void getBasicProfile_returnsProfile_whenCacheMissAndSelfUser() throws Exception {
        String userId = "user-123";
        String userToken = "token-123";
        String cacheKey = Constants.USER + ":basicProfile:" + userId;

        // Use a mutable map for the user profile
        Map<String, Object> userProfile = new HashMap<>();
        userProfile.put(Constants.ROOT_ORG_ID, "org-1");

        // Mock token validation and cache miss
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(cacheService.getCache(cacheKey)).thenReturn(null);

        // Mock Cassandra call for fetchFromDatabase (must return mutable map)
        when(cassandraOperation.getRecordsByPropertiesByKey(
                anyString(), anyString(), anyMap(), anyList(), any()))
                .thenReturn(List.of(userProfile)) // for fetchFromDatabase
                .thenReturn(List.of(Map.of(Constants.ROLE, "role1", Constants.SCOPE, List.of(Map.of(Constants.ORGANISATION_ID, "org-1"))))); // for getUserRoles

        // Mock all other dependencies
        doNothing().when(cacheService).putCache(eq(cacheKey), anyString());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of());
        when(serverProperties.getExtendedFieldsConfig()).thenReturn(List.of());
        when(serverProperties.getDataIndex()).thenReturn(1);
        when(serverProperties.getCacheTtl()).thenReturn(100);
        when(cacheService.hget(anyString(), anyInt(), anyString(), anyInt())).thenReturn("0");
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenReturn("0");
        when(cacheService.getCache("user:communityPostCount:" + userId)).thenReturn("0");

        ApiResponse response = profileService.getBasicProfile(userId, userToken);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult().get("response"));
    }


    @Test
    void getBasicProfile_returnsUnauthorized_whenTokenInvalid() {
        String userId = "user-123";
        String userToken = "invalid-token";
        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(null);

        ApiResponse response = profileService.getBasicProfile(userId, userToken);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getResponseCode());
        assertEquals("Invalid or missing access token", response.getParams().getErrMsg());
    }

    @Test
    void getBasicProfile_returnsNotFound_whenUserProfileIsNull() {
        String userId = "user-123";
        String userToken = "token-123";
        String cacheKey = Constants.USER + ":basicProfile:" + userId;

        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(cacheService.getCache(cacheKey)).thenReturn(null);
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), anyList(), any()))
                .thenReturn(null);

        ApiResponse response = profileService.getBasicProfile(userId, userToken);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Internal server error while fetching profile", response.getParams().getErrMsg());
    }

    @Test
    void getBasicProfile_sanitizesProfile_whenNotSelfUser() throws Exception {
        String userId = "user-123";
        String userToken = "token-123";
        String userIdFromToken = "other-user";
        String cacheKey = Constants.USER + ":basicProfile:" + userId;
        Map<String, Object> userProfile = new HashMap<>();
        userProfile.put(Constants.ROOT_ORG_ID, "org-1");
        Map<String, Object> profileDetails = new HashMap<>();
        profileDetails.put(Constants.PERSONAL_DETAILS, Map.of("a", "b"));
        userProfile.put(Constants.PROFILE_DETAILS, profileDetails);
        String cachedJson = new ObjectMapper().writeValueAsString(userProfile);

        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userIdFromToken);
        when(cacheService.getCache(cacheKey)).thenReturn(cachedJson);
        when(objectMapper.readValue(eq(cachedJson), any(TypeReference.class))).thenReturn(userProfile);
        doNothing().when(cacheService).putCache(eq(cacheKey), anyString());
        when(objectMapper.writeValueAsString(any())).thenReturn(cachedJson);
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of());
        when(serverProperties.getExtendedFieldsConfig()).thenReturn(List.of());
        when(serverProperties.getDataIndex()).thenReturn(1);
        when(serverProperties.getCacheTtl()).thenReturn(100);
        when(cacheService.hget(anyString(), anyInt(), anyString(), anyInt())).thenReturn("0");
        when(cacheService.getCache("user:karmaPoints:" + userId)).thenReturn("0");
        when(cacheService.getCache("user:communityPostCount:" + userId)).thenReturn("0");
        when(cassandraOperation.getRecordsByPropertiesByKey(anyString(), anyString(), anyMap(), anyList(), any()))
                .thenReturn(List.of(Map.of(Constants.ROLE, "role1", Constants.SCOPE, List.of(Map.of(Constants.ORGANISATION_ID, "org-1")))));

        ApiResponse response = profileService.getBasicProfile(userId, userToken);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> resp = (Map<String, Object>) response.getResult().get("response");
        Map<String, Object> details = (Map<String, Object>) resp.get(Constants.PROFILE_DETAILS);
        assertFalse(details.containsKey(Constants.PERSONAL_DETAILS));
    }

    @Test
    void getBasicProfile_handlesExceptionAndReturnsInternalServerError() {
        String userId = "user-123";
        String userToken = "token-123";
        String cacheKey = Constants.USER + ":basicProfile:" + userId;

        when(accessTokenValidator.fetchUserIdFromAccessToken(userToken)).thenReturn(userId);
        when(cacheService.getCache(cacheKey)).thenThrow(new RuntimeException("Redis error"));

        ApiResponse response = profileService.getBasicProfile(userId, userToken);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Internal server error while fetching profile", response.getParams().getErrMsg());
    }

    @Test
    void calculateProfileCompletionPercentage_returnsZero_whenProfileDataIsNull() {
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of("field1"));
        double result = ReflectionTestUtils.invokeMethod(profileService, "calculateProfileCompletionPercentage", null, USER_ID, TOKEN);
        assertEquals(0.0, result);
    }

    @Test
    void calculateProfileCompletionPercentage_returnsZero_whenRequiredFieldsIsNull() {
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(null);
        double result = ReflectionTestUtils.invokeMethod(profileService, "calculateProfileCompletionPercentage", Map.of(), USER_ID, TOKEN);
        assertEquals(0.0, result);
    }

    @Test
    void calculateProfileCompletionPercentage_returnsZero_whenRequiredFieldsIsEmpty() {
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(Collections.emptyList());
        double result = ReflectionTestUtils.invokeMethod(profileService, "calculateProfileCompletionPercentage", Map.of(), USER_ID, TOKEN);
        assertEquals(0.0, result);
    }

    @Test
    void calculateProfileCompletionPercentage_returnsFull_whenAllFieldsPresent() throws Exception {
        Map<String, Object> profileData = new HashMap<>();
        profileData.put("field1", "value1");
        profileData.put("field2", "value2");
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of("field1", "field2"));
        when(serverProperties.getFieldWeight()).thenReturn(50.0);
        Method method = ProfileServiceImpl.class.getDeclaredMethod("isExtendedProfileField", String.class);
        method.setAccessible(true);
        try (MockedStatic<CollectionUtils> ignored = mockStatic(CollectionUtils.class)) {
            ReflectionTestUtils.setField(profileService, "serverConfig", serverProperties); // <-- use serverConfig
            double result = ReflectionTestUtils.invokeMethod(profileService, "calculateProfileCompletionPercentage", profileData, USER_ID, TOKEN);
            assertEquals(100.0, result);
        }
    }

    @Test
    void calculateProfileCompletionPercentage_returnsPartial_whenSomeFieldsMissing() throws Exception {
        Map<String, Object> profileData = new HashMap<>();
        profileData.put("field1", "value1");
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of("field1", "field2"));
        when(serverProperties.getFieldWeight()).thenReturn(50.0);
        double result = ReflectionTestUtils.invokeMethod(profileService, "calculateProfileCompletionPercentage", profileData, USER_ID, TOKEN);
        assertEquals(50.0, result);
    }


    @Test
    void calculateProfileCompletionPercentage_handlesExtendedProfileField_true() throws Exception {
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of("extField"));
        when(serverProperties.getFieldWeight()).thenReturn(100.0);
        when(serverProperties.getExtendedFieldsConfig()).thenReturn(List.of("extField"));

        ProfileServiceImpl testService = new ProfileServiceImpl() {
            protected boolean hasExtendedProfileData(String userId, String field, String token) {
                return true;
            }
        };
        ReflectionTestUtils.setField(testService, "serverConfig", serverProperties);

        double result = ReflectionTestUtils.invokeMethod(
                testService, "calculateProfileCompletionPercentage", Map.of(), USER_ID, TOKEN
        );
        assertEquals(100.0, result);
    }

    @Test
    void calculateProfileCompletionPercentage_handlesExtendedProfileField_false() throws Exception {
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of("extField"));
        when(serverProperties.getExtendedFieldsConfig()).thenReturn(List.of("extField"));
        ProfileServiceImpl spyService = Mockito.spy(profileService);

        // Use reflection to set the method result
        Method method = ProfileServiceImpl.class.getDeclaredMethod(
                "hasExtendedProfileData", String.class, String.class, String.class
        );
        method.setAccessible(true);

        // Optionally, you can use a wrapper to override the method if reflection is not enough

        double result = ReflectionTestUtils.invokeMethod(
                spyService, "calculateProfileCompletionPercentage", Map.of(), USER_ID, TOKEN
        );
        assertEquals(0.0, result);
    }

    @Test
    void calculateProfileCompletionPercentage_handlesServiceHistoryWithProfessionalDetails() throws Exception {
        Map<String, Object> profDetails = new HashMap<>();
        profDetails.put(Constants.PROFESSIONAL_DETAILS, List.of(Map.of("a", "b")));
        Map<String, Object> profileData = new HashMap<>();
        profileData.put(Constants.PROFILE_DETAILS, profDetails);

        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of(Constants.SERVICE_HISTORY));
        when(serverProperties.getFieldWeight()).thenReturn(100.0);
        when(serverProperties.getExtendedFieldsConfig()).thenReturn(List.of(Constants.SERVICE_HISTORY));

        ProfileServiceImpl testService = new ProfileServiceImpl() {
            @Override
            protected boolean hasExtendedProfileData(String userId, String field, String token) {
                return false;
            }
        };
        ReflectionTestUtils.setField(testService, "serverConfig", serverProperties);

        double result = ReflectionTestUtils.invokeMethod(
                testService, "calculateProfileCompletionPercentage", profileData, USER_ID, TOKEN
        );
        assertEquals(100.0, result);
    }

    @Test
    void calculateProfileCompletionPercentage_catchesExceptionAndContinues() throws Exception {
        Map<String, Object> profileData = new HashMap<>();
        profileData.put("field1", "value1");
        when(serverProperties.getProfileCompletionRequiredFields()).thenReturn(List.of("field1", "field2"));
        when(serverProperties.getFieldWeight()).thenReturn(50.0);
        Map<String, Object> spyProfileData = spy(profileData);
        // Use lenient to avoid strict stubbing errors
        lenient().doThrow(new RuntimeException("fail")).when(spyProfileData).getOrDefault(eq("field2"), any());
        double result = ReflectionTestUtils.invokeMethod(profileService, "calculateProfileCompletionPercentage", spyProfileData, USER_ID, TOKEN);
        assertEquals(50.0, result);
    }

    @Test
    void hasExtendedProfileData_returnsTrue_whenLocationDetailsAndStateAndDistrictPresent() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ApiResponse response = new ApiResponse();
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.STATE, "SomeState");
        result.put(Constants.DISTRICT, "SomeDistrict");
        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, result);

        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doReturn(response).when(spyService)
                .readFullExtendedProfile(eq("user-1"), eq(Constants.LOCATION_DETAILS), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", Constants.LOCATION_DETAILS, "token"
        );
        assertTrue(actual);
    }

    @Test
    void hasExtendedProfileData_returnsFalse_whenLocationDetailsMissingStateOrDistrict() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ApiResponse response = new ApiResponse();
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.STATE, "SomeState");
        // Missing DISTRICT
        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, result);

        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doReturn(response).when(spyService)
                .readFullExtendedProfile(eq("user-1"), eq(Constants.LOCATION_DETAILS), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", Constants.LOCATION_DETAILS, "token"
        );
        assertFalse(actual);
    }

    @Test
    void hasExtendedProfileData_returnsTrue_whenContextDataIsNonEmptyCollection() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ApiResponse response = new ApiResponse();
        Map<String, Object> result = new HashMap<>();
        result.put("someContext", List.of(Map.of("a", "b")));
        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, result);

        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doReturn(response).when(spyService)
                .readFullExtendedProfile(eq("user-1"), eq("someContext"), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", "someContext", "token"
        );
        assertTrue(actual);
    }

    @Test
    void hasExtendedProfileData_returnsFalse_whenContextDataIsEmptyCollection() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ApiResponse response = new ApiResponse();
        Map<String, Object> result = new HashMap<>();
        result.put("someContext", Collections.emptyList());
        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, result);

        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doReturn(response).when(spyService)
                .readFullExtendedProfile(eq("user-1"), eq("someContext"), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", "someContext", "token"
        );
        assertFalse(actual);
    }

    @Test
    void hasExtendedProfileData_returnsFalse_whenResponseIsNull() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doReturn(null).when(spyService)
                .readFullExtendedProfile(anyString(), anyString(), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", "context", "token"
        );
        assertFalse(actual);
    }

    @Test
    void hasExtendedProfileData_returnsFalse_whenResponseCodeIsNotOk() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ApiResponse response = new ApiResponse();
        response.setResponseCode(HttpStatus.BAD_REQUEST);

        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doReturn(response).when(spyService)
                .readFullExtendedProfile(anyString(), anyString(), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", "context", "token"
        );
        assertFalse(actual);
    }

    @Test
    void hasExtendedProfileData_returnsFalse_whenExceptionIsThrown() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        ProfileServiceImpl spyService = Mockito.spy(service);
        Mockito.doThrow(new RuntimeException("fail")).when(spyService)
                .readFullExtendedProfile(anyString(), anyString(), anyString());

        boolean actual = ReflectionTestUtils.invokeMethod(
                spyService, "hasExtendedProfileData", "user-1", "context", "token"
        );
        assertFalse(actual);
    }


    @Test
    void analyzeCompetencies_returnsEmptyMaps_whenInputIsEmpty() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Map<String, Object> result = service.analyzeCompetencies(Collections.emptyMap());
        assertTrue(((Map<?, ?>) result.get(Constants.COMPETENCY_AREA_COUNTS)).isEmpty());
        assertTrue(((Map<?, ?>) result.get(Constants.COMPETENCY_THEME_GROUPS)).isEmpty());
    }

    @Test
    void analyzeCompetencies_countsAreasAndGroupsThemesCorrectly() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Map<String, Object> comp1 = Map.of(
                Constants.COMPETENCY_AREA_NAME, "Area1",
                Constants.COMPETENCY_THEME_NAME, "Theme1",
                Constants.COMPETENCY_SUB_THEME_NAME, "Sub1"
        );
        Map<String, Object> comp2 = Map.of(
                Constants.COMPETENCY_AREA_NAME, "Area1",
                Constants.COMPETENCY_THEME_NAME, "Theme1",
                Constants.COMPETENCY_SUB_THEME_NAME, "Sub2"
        );
        Map<String, Object> comp3 = Map.of(
                Constants.COMPETENCY_AREA_NAME, "Area2",
                Constants.COMPETENCY_THEME_NAME, "Theme2",
                Constants.COMPETENCY_SUB_THEME_NAME, "Sub3"
        );
        Map<String, Map<String, Object>> courseMetadata = Map.of(
                "course1", Map.of(Constants.COMPETENCIES_V6, List.of(comp1, comp2)),
                "course2", Map.of(Constants.COMPETENCIES_V6, List.of(comp3))
        );
        Map<String, Object> result = service.analyzeCompetencies(courseMetadata);

        Map<String, Long> areaCounts = (Map<String, Long>) result.get(Constants.COMPETENCY_AREA_COUNTS);
        assertEquals(2, areaCounts.size());
        assertEquals(2L, areaCounts.get("Area1"));
        assertEquals(1L, areaCounts.get("Area2"));

        Map<String, Map<String, Object>> themeGroups = (Map<String, Map<String, Object>>) result.get(Constants.COMPETENCY_THEME_GROUPS);
        assertEquals(2, themeGroups.size());
        assertTrue(((List<?>) themeGroups.get("Theme1").get(Constants.COMPETENCY_SUB_THEME_NAMES)).containsAll(List.of("Sub1", "Sub2")));
        assertTrue(((List<?>) themeGroups.get("Theme1").get(Constants.COURSE_IDS)).contains("course1"));
        assertTrue(((List<?>) themeGroups.get("Theme2").get(Constants.COMPETENCY_SUB_THEME_NAMES)).contains("Sub3"));
        assertTrue(((List<?>) themeGroups.get("Theme2").get(Constants.COURSE_IDS)).contains("course2"));
    }

    @Test
    void analyzeCompetencies_ignoresNonListCompetencies() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Map<String, Map<String, Object>> courseMetadata = Map.of(
                "course1", Map.of(Constants.COMPETENCIES_V6, "notAList")
        );
        Map<String, Object> result = service.analyzeCompetencies(courseMetadata);
        Map<String, Long> areaCounts = (Map<String, Long>) result.get(Constants.COMPETENCY_AREA_COUNTS);
        Map<String, Map<String, Object>> themeGroups = (Map<String, Map<String, Object>>) result.get(Constants.COMPETENCY_THEME_GROUPS);
        assertTrue(areaCounts.isEmpty());
        assertTrue(themeGroups.isEmpty());
    }

    @Test
    void analyzeCompetencies_ignoresNonMapCompetencyItems() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Map<String, Map<String, Object>> courseMetadata = Map.of(
                "course1", Map.of(Constants.COMPETENCIES_V6, List.of("notAMap"))
        );
        Map<String, Object> result = service.analyzeCompetencies(courseMetadata);
        Map<String, Long> areaCounts = (Map<String, Long>) result.get(Constants.COMPETENCY_AREA_COUNTS);
        Map<String, Map<String, Object>> themeGroups = (Map<String, Map<String, Object>>) result.get(Constants.COMPETENCY_THEME_GROUPS);
        assertTrue(areaCounts.isEmpty());
        assertTrue(themeGroups.isEmpty());
    }

    @Test
    void analyzeCompetencies_handlesNullSubThemeName() {
        ProfileServiceImpl service = new ProfileServiceImpl();
        Map<String, Object> comp = new HashMap<>();
        comp.put(Constants.COMPETENCY_AREA_NAME, "Area1");
        comp.put(Constants.COMPETENCY_THEME_NAME, "Theme1");
        comp.put(Constants.COMPETENCY_SUB_THEME_NAME, null);
        Map<String, Map<String, Object>> courseMetadata = Map.of(
                "course1", Map.of(Constants.COMPETENCIES_V6, List.of(comp))
        );
        Map<String, Object> result = service.analyzeCompetencies(courseMetadata);
        Map<String, Map<String, Object>> themeGroups = (Map<String, Map<String, Object>>) result.get(Constants.COMPETENCY_THEME_GROUPS);
        List<?> subThemes = (List<?>) themeGroups.get("Theme1").get(Constants.COMPETENCY_SUB_THEME_NAMES);
        // Accepts either an empty list or a list containing only nulls
        assertFalse(subThemes.isEmpty() || subThemes.stream().allMatch(Objects::isNull));
    }

}
