package com.igot.cb.profile.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.authentication.util.AccessTokenValidator;
import com.igot.cb.profile.entity.CustomFieldEntity;
import com.igot.cb.profile.repository.CustomFieldRepository;
import com.igot.cb.transactional.cassandrautils.CassandraOperation;
import com.igot.cb.transactional.redis.cache.CacheService;
import com.igot.cb.transactional.service.RequestHandlerServiceImpl;
import com.igot.cb.util.*;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.IntStream;
@Service
@SuppressWarnings("unchecked")
public class ProfileServiceImpl implements ProfileService {

    @Autowired
    private AccessTokenValidator accessTokenValidator;

    @Autowired
    private CbServerProperties serverConfig;

    @Autowired
    private CassandraOperation cassandraOperation;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ProjectUtil projectUtil;

    @Autowired
    private RequestHandlerServiceImpl requestHandlerService;

    @Autowired
    private CustomFieldRepository customFieldRepository;

    private static final Logger logger = LoggerFactory.getLogger(ProfileServiceImpl.class);

    // -------------------- Service METHODS --------------------

    @Override
    public ApiResponse saveExtendedProfile(Map<String, Object> request, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.extendedProfile.create");
        Map<String, Object> requestData = (Map<String, Object>) request.get(Constants.REQUEST);
        String userId = (String) requestData.get(Constants.USER_ID_RQST);
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(userToken);

        if (!StringUtils.equalsIgnoreCase(userIdFromToken, userId)) {
            ProjectUtil.errorResponse(response, "Invalid UserId in the request", HttpStatus.BAD_REQUEST);
            return response;
        }

        String validationError = validateRequestContextTypes(requestData, serverConfig.getContextType());
        if (StringUtils.isNotBlank(validationError)) {
            ProjectUtil.errorResponse(response, validationError, HttpStatus.BAD_REQUEST);
            return response;
        }

        String errMsg = validateUserExtendedProfileRequest(requestData);
        if (StringUtils.isNotBlank(errMsg)) {
            ProjectUtil.errorResponse(response, errMsg, HttpStatus.BAD_REQUEST);
            return response;
        }

        List<Map<String, Object>> savedDataWithUUIDs = new ArrayList<>();
        for (String contextType : serverConfig.getContextType()) {
            List<Map<String, Object>> incomingList = (List<Map<String, Object>>) requestData.get(contextType);
            if (incomingList == null || incomingList.isEmpty())
                continue;

            List<Map<String, Object>> dataWithUUIDs = addUUIDs(incomingList);
            List<Map<String, Object>> existingList = getExistingContextData(userId, contextType);

            if(Constants.ACHIEVEMENTS.equalsIgnoreCase(contextType)) {
                mergeAndSortByIssuedDateOrTitle(existingList, dataWithUUIDs);
            }else{
                existingList.addAll(dataWithUUIDs);
            }

            //sortContextData(existingList, contextType);
            if (!saveContextData(userId, contextType, existingList)) {
                ProjectUtil.errorResponse(response, "Failed to save data for contextType: " + contextType,
                        HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            cacheService.putCache(buildCacheKey("user:extendedProfile", contextType, userId), existingList);
            updateExtendedProfileAllCache(userId, contextType, existingList);
            savedDataWithUUIDs.addAll(dataWithUUIDs);
        }

        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESULT, savedDataWithUUIDs);
        return response;
    }

    @Override
    public ApiResponse updateExtendedProfile(Map<String, Object> request, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.extendedProfile.update");
        Map<String, Object> requestData = (Map<String, Object>) request.get(Constants.REQUEST);
        String userId = (String) requestData.get(Constants.USER_ID_RQST);
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(userToken);

        if (!StringUtils.equalsIgnoreCase(userIdFromToken, userId)) {
            ProjectUtil.errorResponse(response, "Invalid UserId in the request", HttpStatus.BAD_REQUEST);
            return response;
        }

        for (String contextType : serverConfig.getContextType()) {
            List<Map<String, Object>> incomingList = (List<Map<String, Object>>) requestData.get(contextType);
            if (incomingList == null || incomingList.isEmpty())
                continue;

            List<Map<String, Object>> existingData = getExistingContextData(userId, contextType);
            Map<String, Map<String, Object>> dataMap = existingData.stream()
                    .filter(e -> e.get(Constants.UUID) != null)
                    .collect(Collectors.toMap(e -> (String) e.get(Constants.UUID), e -> e));

            for (Map<String, Object> item : incomingList) {
                String uuid = (String) item.get(Constants.UUID);
                if (uuid != null && dataMap.containsKey(uuid)) {
                    dataMap.get(uuid).putAll(item);
                } else {
                    ProjectUtil.errorResponse(response, "Invalid or missing UUID in incoming data.",
                            HttpStatus.BAD_REQUEST);
                    return response;
                }
            }

            List<Map<String, Object>> mergedList = new ArrayList<>(dataMap.values());
            //sortContextData(mergedList, contextType);

            if (Constants.ACHIEVEMENTS.equalsIgnoreCase(contextType)) {
                mergeAndSortByIssuedDateOrTitle(mergedList, new ArrayList<>());
            }
            if (!saveContextData(userId, contextType, mergedList)) {
                ProjectUtil.errorResponse(response, "Failed to update data for contextType: " + contextType,
                        HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            cacheService.putCache(buildCacheKey("user:extendedProfile", contextType, userId), mergedList);
            updateExtendedProfileAllCache(userId, contextType, mergedList);
        }

        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, Constants.SUCCESS);
        return response;
    }

    @Override
    public ApiResponse deleteExtendedProfile(Map<String, Object> request, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.extendedProfile.delete");
        Map<String, Object> requestData = (Map<String, Object>) request.get(Constants.REQUEST);
        String userId = (String) requestData.get(Constants.USER_ID_RQST);
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(userToken);

        if (!StringUtils.equalsIgnoreCase(userIdFromToken, userId)) {
            ProjectUtil.errorResponse(response, "Invalid UserId in the request", HttpStatus.BAD_REQUEST);
            return response;
        }

        for (String contextType : serverConfig.getContextType()) {
            List<Map<String, Object>> toDeleteList = (List<Map<String, Object>>) requestData.get(contextType);
            if (toDeleteList == null || toDeleteList.isEmpty())
                continue;

            Set<String> uuids = toDeleteList.stream()
                    .map(e -> (String) e.get(Constants.UUID))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<Map<String, Object>> existingData = getExistingContextData(userId, contextType);
            existingData.removeIf(e -> uuids.contains(e.get(Constants.UUID)));
            //sortContextData(existingData, contextType);

            if (!saveContextData(userId, contextType, existingData)) {
                ProjectUtil.errorResponse(response, "Failed to delete data for contextType: " + contextType,
                        HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            cacheService.putCache(buildCacheKey("user:extendedProfile", contextType, userId), existingData);
            updateExtendedProfileAllCache(userId, contextType, existingData);
        }

        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, Constants.SUCCESS);
        return response;
    }

    @Override
    public ApiResponse getExtendedProfileSummary(String userId, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.extendedProfile.read");

        if (accessTokenValidator.fetchUserIdFromAccessToken(userToken) == null) {
            ProjectUtil.errorResponse(response, "Invalid UserId in the request", HttpStatus.BAD_REQUEST);
            return response;
        }

        String redisKey = buildCacheKey("user:extendedProfile", "all", userId);
        try {
            String cachedJson = cacheService.getCache(redisKey);
            if (cachedJson != null) {
                Map<String, Object> cachedResult = mapper.readValue(cachedJson, Map.class);
                Map<String, Object> limitedResult = buildLimitedSummary(cachedResult);
                response.setResponseCode(HttpStatus.OK);
                response.put(Constants.RESPONSE, limitedResult);
                return response;
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch summary from cache for userId {}: {}", userId, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        for (String contextType : serverConfig.getContextType()) {
            List<Map<String, Object>> data = getExistingContextData(userId, contextType);
            if (!data.isEmpty()) {
                Map<String, Object> contextSummary = new HashMap<>();
                contextSummary.put(Constants.COUNT, data.size());
                contextSummary.put(Constants.DATA, data.stream().limit(2).collect(Collectors.toList()));
                result.put(contextType, contextSummary);
            }
        }

        if (result.isEmpty()) {
            ProjectUtil.errorResponse(response, "No data found for user.", HttpStatus.NO_CONTENT);
            return response;
        }

        result.put(Constants.USERID_KEY, userId);
        try {
            cacheService.putCache(redisKey, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.warn("Failed to cache extended profile summary for userId {}: {}", userId, e.getMessage());
        }

        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE, result);
        return response;
    }

    @Override
    public ApiResponse readFullExtendedProfile(String userId, String contextType, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.extendedProfile.read");
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(userToken);

        if (userIdFromToken == null) {
            ProjectUtil.errorResponse(response, "Invalid UserId in the request", HttpStatus.BAD_REQUEST);
            return response;
        }

        String redisKey = buildCacheKey("user:extendedProfile", contextType, userId);
        List<Map<String, Object>> contextData = null;

        try {
            String cachedJson = cacheService.getCache(redisKey);
            if (cachedJson != null) {
                contextData = projectUtil.parseListOfMap(cachedJson);
            }
        } catch (Exception e) {
            logger.warn("Error reading from cache for key {}: {}", redisKey, e.getMessage());
        }

        if (contextData == null) {
            contextData = getExistingContextData(userId, contextType);
            if (contextData == null || contextData.isEmpty()) {
                ProjectUtil.errorResponse(response, "No data found for user.", HttpStatus.NO_CONTENT);
                return response;
            }
            try {
                cacheService.putCache(redisKey, mapper.writeValueAsString(contextData));
            } catch (Exception e) {
                logger.warn("Failed to cache data for key {}: {}", redisKey, e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put(contextType, contextData);
        result.put(Constants.USER_ID_RQST, userId);
        result.put(Constants.COUNT, contextData.size());

        response.setResponseCode(HttpStatus.OK);
        response.put(Constants.RESPONSE,
                contextType.equalsIgnoreCase(Constants.LOCATION_DETAILS) ? contextData.get(0) : result);
        return response;
    }

    @Override
    public ApiResponse getBasicProfile(String userId, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.getBasicProfile.read");
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(userToken);

        if (userIdFromToken == null) {
            ProjectUtil.errorResponse(response, "Invalid or missing access token", HttpStatus.UNAUTHORIZED);
            return response;
        }

        boolean isSelfUser = userIdFromToken.equalsIgnoreCase(userId);
        String cacheKey = Constants.USER + ":basicProfile:" + userId;

        try {
            String cachedJson = cacheService.getCache(cacheKey);
            Map<String, Object> userProfile;
            if (StringUtils.isNotEmpty(cachedJson)) {
                userProfile = mapper.readValue(cachedJson, new TypeReference<Map<String, Object>>() {
                });
            }else{
                userProfile = fetchFromDatabase(userId);
            }
            UserUtility.decryptSpecificUserData(userProfile, Arrays.asList(Constants.USERNAME_LOWERCASE));

            if (userProfile == null) {
                response.setResponseCode(HttpStatus.NOT_FOUND);
                response.put(Constants.RESPONSE, Collections.emptyMap());
                return response;
            }

            double completion = calculateProfileCompletionPercentage(userProfile,
                    userId, userToken);
            int karmaPoints = getUserKarmaPoints(userId);
            int certificateCount = getIssuedCertificateCount(userId);
            int postCount = getUserPostCount(userId);
            userProfile.put(Constants.PROFILE_COMPLETION_PERCENTAGE, completion);
            userProfile.put(Constants.KARMA_POINTS,karmaPoints);
            userProfile.put(Constants.CERTIFICATE_COUNT, certificateCount);
            userProfile.put(Constants.POSTCOUNT, postCount);
            userProfile.put("roles", getUserRoles(userId,(String)userProfile.get(Constants.ROOT_ORG_ID)));

            if (!isSelfUser) {
                sanitizeProfile(userProfile);
            }

            cacheService.putCache(cacheKey,userProfile);
            Map<String,Object> responseMap = new HashMap<>();
            responseMap.put("response", userProfile);
            response.setResponse(responseMap);
        } catch (Exception e) {
            logger.error("Error fetching basic profile for userId: {}", userId, e);
            ProjectUtil.errorResponse(response, "Internal server error while fetching profile",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return response;
    }

    @Override
    public ApiResponse listCompetencies(String userId, String userToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.listCompetencies.read");
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(userToken);

        if (userIdFromToken == null) {
            ProjectUtil.errorResponse(response, "Invalid or missing access token", HttpStatus.UNAUTHORIZED);
            return response;
        }

        String cacheKey = Constants.USER + ":competencies:" + userId;
        try {
            String cachedJson = cacheService.getCache(cacheKey);
            Map<String, Object> competencies = (cachedJson != null) ? projectUtil.parseMap(cachedJson) : Map.of();

            if (competencies.isEmpty()) {
                Map<String, Object> queryParams = Map.of(Constants.USERID_KEY, userId);
                List<String> fields = Arrays.asList(Constants.USERID_KEY, Constants.COURSE_ID, Constants.BATCH_ID,
                        Constants.ACTIVE, Constants.STATUS);
                List<Map<String, Object>> allEnrolmentRecords = cassandraOperation.getAllRecordsByPrimaryKey(
                        Constants.KEYSPACE_SUNBIRD_COURSES,
                        Constants.TABLE_USER_ENROLMENTS, queryParams, fields, 100);
                List<String> completedCourseIdList = allEnrolmentRecords.stream()
                        .filter(map -> Boolean.TRUE.equals(map.get(Constants.ACTIVE_LOWERCASE)) && Integer.valueOf(2).equals(map.get(Constants.STATUS)))
                        .map(map -> map.get(Constants.COURSE_ID))
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .collect(Collectors.toList());
                if (completedCourseIdList.isEmpty()) {
                    ProjectUtil.errorResponse(response, "No competencies found for user.", HttpStatus.NO_CONTENT);
                    return response;
                }
                Map<String, Map<String, Object>> courseMetadata = getCourseMetadataBatched(completedCourseIdList, 100,
                        Arrays.asList(Constants.COURSE_ID, Constants.COURSE_CATEGORY, Constants.COMPETENCIES_V6,
                                Constants.NAME));
                competencies = analyzeCompetencies(courseMetadata);
                
                if (competencies.isEmpty()) {
                    ProjectUtil.errorResponse(response, "No competencies found for user.", HttpStatus.NO_CONTENT);
                    return response;
                }
                cacheService.putCache(cacheKey, mapper.writeValueAsString(competencies));
            }

            response.setResponseCode(HttpStatus.OK);
            response.put(Constants.RESPONSE, competencies);
        } catch (Exception e) {
            logger.error("Error fetching competencies for userId: {}", userId, e);
            ProjectUtil.errorResponse(response, "Internal server error while fetching competencies",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return response;
    }

    // -------------------- HELPER METHODS --------------------

    private List<Map<String, Object>> addUUIDs(List<Map<String, Object>> list) {
        return list.stream().peek(item -> item.put(Constants.UUID, UUID.randomUUID().toString()))
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> getExistingContextData(String userId, String contextType) {
        Map<String, Object> query = Map.of(Constants.USERID_KEY, userId, Constants.CONTEXT_TYPE, contextType);
        List<Map<String, Object>> rows = cassandraOperation.getRecordsByPropertiesByKey(Constants.KEYSPACE_SUNBIRD,
                Constants.TABLE_USER_EXTENDED_PROFILE, query, null, null);
        if (rows != null && !rows.isEmpty()) {
            String json = (String) rows.get(0).get(Constants.CONTEXT_DATA);
            try {
                return projectUtil.parseListOfMap(json);
            } catch (IOException e) {
                logger.error("Error parsing existing data for userId: {}, contextType: {}", userId, contextType);
            }
        }
        return new ArrayList<>();
    }

    private boolean saveContextData(String userId, String contextType, List<Map<String, Object>> dataList) {
        try {
            String finalJson = mapper.writeValueAsString(dataList);
            Map<String, Object> query = new HashMap<>();
            query.put(Constants.USERID_KEY, userId);
            query.put(Constants.CONTEXT_TYPE, contextType);
            query.put(Constants.CONTEXT_DATA, finalJson);
            ApiResponse insertResponse = (ApiResponse) cassandraOperation.insertRecord(Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_USER_EXTENDED_PROFILE, query);
            return Constants.SUCCESS.equalsIgnoreCase((String) insertResponse.get(Constants.RESPONSE));
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize context data for userId: {}, contextType: {}", userId, contextType);
        }
        return false;
    }

    private void sortContextData(List<Map<String, Object>> dataList, String contextType) {
        Comparator<Map<String, Object>> comparator = getSortingComparator(contextType);
        if (comparator != null) {
            dataList.sort(comparator.reversed());
        }
    }

    private Comparator<Map<String, Object>> getSortingComparator(String contextType) {
        return switch (contextType) {
            case Constants.SERVICE_HISTORY ->
                Comparator.comparing(map -> OffsetDateTime.parse((String) map.get(Constants.START_DATE)));
            case Constants.EDUCATIONAL_QUALIFICATIONS ->
                Comparator.comparing(map -> Integer.parseInt((String) map.get(Constants.START_YEAR)));
            case Constants.ACHIVEMENTS ->
                Comparator.comparing(map -> OffsetDateTime.parse((String) map.get(Constants.ISSUED_DATE)));
            default -> null;
        };
    }

    private String buildCacheKey(String prefix, String contextType, String userId) {
        return String.join(":", prefix, contextType, userId);
    }

    private void updateExtendedProfileAllCache(String userId, String contextType,
            List<Map<String, Object>> updatedContextData) {
        String allKey = "user:extendedProfile:all:" + userId;
        try {
            String allJson = cacheService.getCache(allKey);
            Map<String, Object> allProfileData = (allJson != null && !allJson.isEmpty())
                    ? mapper.readValue(allJson, new TypeReference<>() {
                    })
                    : new HashMap<>();
            Map<String, Object> updatedContext = new HashMap<>();
            updatedContext.put(Constants.DATA, updatedContextData);
            updatedContext.put(Constants.COUNT, updatedContextData != null ? updatedContextData.size() : 0);
            allProfileData.put(contextType, updatedContext);
            cacheService.putCache(allKey, mapper.writeValueAsString(allProfileData));
        } catch (Exception e) {
            logger.error("Error updating extendedProfile all cache for userId {}: {}", userId, e.getMessage());
        }
    }

    private String validateUserExtendedProfileRequest(Map<String, Object> requestData) {
        if (requestData == null)
            return "Request data is missing.";
        List<String> errList = new ArrayList<>();
        validateFieldsForList(requestData, Constants.EDUCATIONAL_QUALIFICATIONS,
                serverConfig.getEducationalQualificationMandatoryFields(), errList, false);
        validateFieldsForList(requestData, Constants.ACHIVEMENTS, serverConfig.getAchievementsMandatoryFields(),
                errList, false);
        validateFieldsForList(requestData, Constants.SERVICE_HISTORY, serverConfig.getServiceHistoryMandatoryFields(),
                errList, true);
        return errList.isEmpty() ? "" : "Failed Due To Missing or Invalid Params - " + String.join(", ", errList) + ".";
    }

    private void validateFieldsForList(Map<String, Object> requestData, String listKey, String mandatoryFields,
            List<String> errList, boolean allowSkipEndDate) {
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) requestData.get(listKey);
        if (dataList != null) {
            for (Map<String, Object> data : dataList) {
                String error = validateFields(data, mandatoryFields, allowSkipEndDate);
                if (!error.isEmpty()) {
                    errList.add(error);
                }
            }
        }
    }

    private String validateFields(Map<String, Object> data, String mandatoryFields, boolean allowSkipEndDate) {
        StringBuilder errorMessages = new StringBuilder();
        for (String field : mandatoryFields.split(",")) {
            if (allowSkipEndDate && Constants.END_DATE.equals(field)) {
                Object currentlyWorking = data.get(Constants.CURRENTLY_WORKING);
                if (Constants.TRUE.equalsIgnoreCase(String.valueOf(currentlyWorking))) {
                    continue;
                }
            }
            if (StringUtils.isBlank((String) data.get(field))) {
                errorMessages.append(field).append(" is mandatory. ");
            }
        }
        return errorMessages.toString();
    }

    private String validateRequestContextTypes(Map<String, Object> requestData, String[] contextTypes) {
        Set<String> allowedKeys = new HashSet<>(Arrays.asList(contextTypes));
        allowedKeys.add(Constants.USER_ID_RQST);
        return requestData.keySet().stream()
                .filter(key -> !allowedKeys.contains(key))
                .findFirst()
                .map(key -> "Invalid context type in request: " + key)
                .orElse(null);
    }

    private Map<String, Object> fetchFromDatabase(String userId) {
        Map<String, Object> queryParams = Map.of(Constants.ID, userId);
        List<Map<String, Object>> records = cassandraOperation.getRecordsByPropertiesByKey(
                Constants.KEYSPACE_SUNBIRD, Constants.USER, queryParams, serverConfig.getBasicProfileFields(), null);

        if (records == null || records.isEmpty())
            return null;
        Map<String, Object> record = records.get(0);
        String profileDetailsJson = (String) record.get(Constants.PROFILE_DETAILS);

        try {
            if (profileDetailsJson != null) {
                Map<String, Object> profileDetailsMap = projectUtil.parseMap(profileDetailsJson);
                record.put(Constants.PROFILE_DETAILS, profileDetailsMap);
            }
        } catch (IOException e) {
            logger.warn("Invalid profileDetails JSON for userId: {}", userId, e);
            record.remove(Constants.PROFILE_DETAILS);
        }

        return record;
    }

    private void sanitizeProfile(Map<String, Object> profile) {
        Object detailsObj = profile.get(Constants.PROFILE_DETAILS);
        if (detailsObj instanceof Map<?, ?> detailsMap && detailsMap.containsKey(Constants.PERSONAL_DETAILS)) {
            detailsMap.remove(Constants.PERSONAL_DETAILS);
            logger.info("Removed personalDetails for non-self user.");
        }
    }

    protected double calculateProfileCompletionPercentage(Map<String, Object> profileData,
                                                          String userId, String userToken) {
        List<String> requiredFields = serverConfig.getProfileCompletionRequiredFields();
        if (profileData == null || requiredFields == null || requiredFields.isEmpty())
            return 0.0;

        double totalCompletion = 0.0;
        Map<String, Object> nestedData = Optional.ofNullable(profileData.get(Constants.PROFILE_DETAILS))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .orElse(Collections.emptyMap());

        for (String field : requiredFields) {
            boolean isFilled;
            try {
                if (isExtendedProfileField(field)) {
                    isFilled = hasExtendedProfileData(userId, field, userToken)
                            || (Constants.SERVICE_HISTORY.equalsIgnoreCase(field) &&
                            Optional.ofNullable(profileData.get(Constants.PROFILE_DETAILS))
                                    .filter(Map.class::isInstance)
                                    .map(Map.class::cast)
                                    .map(details -> details.get(Constants.PROFESSIONAL_DETAILS))
                                    .filter(List.class::isInstance)
                                    .map(List.class::cast)
                                    .map(CollectionUtils::isNotEmpty)
                                    .orElse(false));
                } else {
                    if (Constants.EMPLOYMENT_DETAILS.equalsIgnoreCase(field)) {
                        isFilled = Optional.ofNullable(profileData.get(Constants.PROFILE_DETAILS))
                                .filter(Map.class::isInstance)
                                .map(Map.class::cast)
                                .map(details -> details.get(Constants.EMPLOYMENT_DETAILS))
                                .filter(Map.class::isInstance)
                                .map(Map.class::cast)
                                .map(empDetails -> empDetails.get(Constants.ABOUT_ME))
                                .map(Object::toString)
                                .filter(aboutMe -> !aboutMe.trim().isEmpty())
                                .isPresent();
                    }else {
                        Object value = profileData.getOrDefault(field, nestedData.get(field));
                        isFilled = value != null && !value.toString().trim().isEmpty();
                    }
                }
            } catch (Exception e) {
                logger.warn("Exception checking field '{}' for user '{}': {}", field, userId, e.getMessage());
                isFilled = false;
            }
            if (isFilled)
                totalCompletion += serverConfig.getFieldWeight();
        }

        return Math.min(100.0, Math.round(totalCompletion * 10.0) / 10.0);
    }

    private boolean isExtendedProfileField(String field) {
        return serverConfig.getExtendedFieldsConfig().stream()
                .anyMatch(f -> f.equalsIgnoreCase(field));
    }

    protected boolean hasExtendedProfileData(String userId, String contextType, String userToken) {
        try {
            ApiResponse response = readFullExtendedProfile(userId, contextType, userToken);
            if (response != null && response.getResponseCode() == HttpStatus.OK) {
                Map<String, Object> result = (Map<String, Object>) response.get(Constants.RESPONSE);
                if (Constants.LOCATION_DETAILS.equalsIgnoreCase(contextType))
                    return Stream.of(Constants.STATE, Constants.DISTRICT).allMatch(result::containsKey);
                Object contextData = result.get(contextType);
                return contextData instanceof Collection && !((Collection<?>) contextData).isEmpty();
            }
        } catch (Exception e) {
            logger.error("Error checking extended profile data for userId {} and contextType {}: {}", userId,
                    contextType, e.getMessage());
        }
        return false;
    }

    public Map<String, Map<String, Object>> getCourseMetadataBatched(List<String> courseIds, int batchSize,
            List<String> fields) {
        Map<String, Map<String, Object>> allResults = new LinkedHashMap<>();
        if (courseIds == null || courseIds.isEmpty())
            return allResults;

        for (int i = 0; i < courseIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, courseIds.size());
            List<String> batch = courseIds.subList(i, end);

            Map<String, String> courseDetailsStrMap = cacheService.getCourseMetadataAsJsonString(batch);

            for (int j = 0; j < batch.size(); j++) {
                String courseId = batch.get(j);
                String json = courseDetailsStrMap.get(courseId);

                if (json != null) {
                    try {
                        Map<String, Object> parsed = projectUtil.parseMap(json);
                        if (parsed == null || parsed.isEmpty()) {
                            logger.warn("Parsed JSON for key {} is empty or null", courseId);
                            continue;
                        }

                        if (fields == null || fields.isEmpty()) {
                            allResults.put(courseId, parsed);
                        } else {
                            // Filter only requested fields
                            Map<String, Object> filtered = parsed.entrySet().stream()
                                    .filter(e -> fields.contains(e.getKey()))
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                            if (!filtered.isEmpty()) {
                                allResults.put(courseId, filtered);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to parse JSON for key {}: {}", courseId, e.getMessage(), e);
                    }
                } else {
                    logger.warn("No cached data found for courseId: {}", courseId);
                }
            }
        }
        return allResults;
    }

    public Map<String, Object> analyzeCompetencies(Map<String, Map<String, Object>> courseMetadata) {
        // Result containers
        Map<String, Long> areaCountMap = new HashMap<>();
        Map<String, Map<String, Object>> themeGroupMap = new HashMap<>();

        for (Map.Entry<String, Map<String, Object>> entry : courseMetadata.entrySet()) {
            String courseId = entry.getKey();
            Map<String, Object> course = entry.getValue();

            Object compObj = course.get(Constants.COMPETENCIES_V6);
            if (!(compObj instanceof List<?> competencies))
                continue;

            for (Object comp : competencies) {
                if (!(comp instanceof Map<?, ?> compMap))
                    continue;

                String areaName = String.valueOf(compMap.get(Constants.COMPETENCY_AREA_NAME));
                String themeName = String.valueOf(compMap.get(Constants.COMPETENCY_THEME_NAME));
                String subThemeName = String.valueOf(compMap.get(Constants.COMPETENCY_SUB_THEME_NAME));

                // 1. Count by competencyAreaName
                areaCountMap.merge(areaName, 1L, Long::sum);

                // 2. Group by competencyThemeName
                themeGroupMap.computeIfAbsent(themeName, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put(Constants.COMPETENCY_SUB_THEME_NAMES, new HashSet<String>());
                    m.put(Constants.COURSE_IDS, new HashSet<String>());
                    return m;
                });

                Set<String> subThemes = (Set<String>) themeGroupMap.get(themeName).get(Constants.COMPETENCY_SUB_THEME_NAMES);
                Set<String> courseIds = (Set<String>) themeGroupMap.get(themeName).get(Constants.COURSE_IDS);

                if (subThemeName != null && !subThemeName.isBlank())
                    subThemes.add(subThemeName);
                courseIds.add(courseId);
            }
        }

        // Prepare final output
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.COMPETENCY_AREA_COUNTS, areaCountMap);

        // Convert sets to lists for serialization/final response
        Map<String, Map<String, Object>> groupedThemes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : themeGroupMap.entrySet()) {
            groupedThemes.put(entry.getKey(), Map.of(
                    Constants.COMPETENCY_SUB_THEME_NAMES,
                    new ArrayList<>((Set<?>) entry.getValue().get(Constants.COMPETENCY_SUB_THEME_NAMES)),
                    Constants.COURSE_IDS, new ArrayList<>((Set<?>) entry.getValue().get(Constants.COURSE_IDS))));
        }

        result.put(Constants.COMPETENCY_THEME_GROUPS, groupedThemes);
        return result;
    }

    private Map<String, Object> buildLimitedSummary(Map<String, Object> fullData) {
        Map<String, Object> limitedData = new HashMap<>();

        for (Map.Entry<String, Object> entry : fullData.entrySet()) {
            String key = entry.getKey();

            if (!(entry.getValue() instanceof Map)) {
                limitedData.put(key, entry.getValue());
                continue;
            }

            Map<String, Object> contextBlock = (Map<String, Object>) entry.getValue();
            Object dataObj = contextBlock.get(Constants.DATA);

            if (dataObj instanceof List) {
                List<Map<String, Object>> dataList = (List<Map<String, Object>>) dataObj;
                Map<String, Object> limitedBlock = new HashMap<>();
                limitedBlock.put(Constants.COUNT, contextBlock.get(Constants.COUNT));
                limitedBlock.put(Constants.DATA, dataList.size() > 2 ? dataList.subList(0, 2) : dataList);
                limitedData.put(key, limitedBlock);
            } else {
                limitedData.put(key, contextBlock);
            }
        }

        return limitedData;
    }

    private int getUserKarmaPoints(String userId) {
        String redisKey = "user:karmaPoints:" + userId;

        try {
            String redisValue = cacheService.getCache(redisKey);
            if (redisValue != null) {
                return Integer.parseInt(redisValue);
            }

            List<Map<String, Object>> records = cassandraOperation.getRecordsByPropertiesByKey(Constants.KEYSPACE_SUNBIRD,Constants.USER_KARMA_POINTS_SUMMARY_TABLE,
                    Map.of(Constants.USERID_KEY, userId), List.of(Constants.TOTAL_POINTS), userId);
            int totalPoints = 0;
            if(!CollectionUtils.isEmpty(records)){
                totalPoints=(int) records.get(0).get(Constants.TOTAL_POINTS);
            }

            cacheService.putCache(redisKey, String.valueOf(totalPoints));
            return totalPoints;
        } catch (Exception e) {
            logger.warn("Failed to fetch karma points for userId {}: {}", userId, e.getMessage());
            return 0;
        }
    }


    private int getIssuedCertificateCount(String userId) {
        String redisKey = serverConfig.getCertificateCountRedisKey();

        try {
            String cachedValue = cacheService.hget(redisKey,serverConfig.getDataIndex(),userId,serverConfig.getCacheTtl());
            if (cachedValue != null) {
                return Integer.parseInt(cachedValue);
            }
            List<Map<String, Object>> courseRecords = cassandraOperation.getRecordsByPropertiesByKey(
                    Constants.KEYSPACE_SUNBIRD_COURSES,
                    Constants.USER_ENROLMENTS,
                    Map.of(Constants.USERID_KEY, userId),
                    List.of(Constants.ISSUED_CERTIFICATES),
                    userId
            );

            int totalIssuedCertificates = 0;
            totalIssuedCertificates += courseRecords.stream()
                    .filter(MapUtils::isNotEmpty)
                    .map(record -> record.get(Constants.ISSUED_CERTIFICATES_KEY))
                    .filter(certObj -> certObj instanceof List<?>)
                    .map(certObj -> (List<?>) certObj)
                    .filter(CollectionUtils::isNotEmpty)
                    .mapToInt(List::size)
                    .sum();

            List<Map<String, Object>> eventRecords = cassandraOperation.getRecordsByPropertiesByKey(
                    Constants.KEYSPACE_SUNBIRD_COURSES,
                    Constants.USER_ENTITY_ENROLMENTS,
                    Map.of(Constants.USERID_KEY, userId),
                    List.of(Constants.ISSUED_CERTIFICATES,Constants.PROGRESS_KEY,Constants.STATUS),
                    userId
            );

            int certificatesFromEvents = eventRecords.stream()
                    .filter(MapUtils::isNotEmpty)
                    .filter(r -> r.get(Constants.STATUS) instanceof Number && ((Number)r.get(Constants.STATUS)).intValue() == 2)
                    .filter(r -> r.get(Constants.PROGRESS_KEY) instanceof Number && ((Number)r.get(Constants.PROGRESS_KEY)).intValue() == 100)
                    .map(r -> r.get(Constants.ISSUED_CERTIFICATES_KEY))
                    .filter(obj -> obj instanceof List<?>)
                    .map(obj -> (List<?>) obj)
                    .filter(CollectionUtils::isNotEmpty)
                    .mapToInt(List::size)
                    .sum();
            totalIssuedCertificates += certificatesFromEvents;
            cacheService.hset(redisKey,serverConfig.getDataIndex(),userId, String.valueOf(totalIssuedCertificates));
            return totalIssuedCertificates;

        } catch (Exception e) {
            logger.warn("Failed to fetch issued certificate count for userId {}: {}", userId, e.getMessage());
            return 0;
        }
    }

    private int getUserPostCount(String userId) {
        String redisKey = "user:communityPostCount:" + userId;

        try {
            String cachedValue = cacheService.getCache(redisKey);
            if (cachedValue != null) {
                return Integer.parseInt(cachedValue);
            }

            int postCount = fetchPostCountFromApi(userId);
            cacheService.putCache(redisKey, String.valueOf(postCount));
            return postCount;

        } catch (Exception e) {
            logger.warn("Failed to fetch post count for userId {}: {}", userId, e.getMessage());
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private int fetchPostCountFromApi(String userId) {
        String uri = serverConfig.getCommunityBaseUrl() + serverConfig.getCommunityPostCountApiUrl() + userId;

        try {
            Map<String, Object> response = (Map<String, Object>) requestHandlerService.fetchUsingGetWithHeadersProfile(uri, null);

            return Optional.ofNullable(response)
                    .filter(MapUtils::isNotEmpty)
                    .map(rd -> (Map<String, Object>) rd.get(Constants.RESULT))
                    .filter(MapUtils::isNotEmpty)
                    .map(result -> result.get(Constants.POSTCOUNT))
                    .filter(pc -> pc instanceof Integer)
                    .map(Integer.class::cast)
                    .orElse(0);

        } catch (Exception e) {
            logger.warn("Failed to fetch post count from community API for userId {}: {}", userId, e.getMessage());
            return 0;
        }
    }

    public List<String> getUserRoles(String userId, String rootOrgId) {
        List<Map<String, Object>> records = cassandraOperation.getRecordsByPropertiesByKey(
                Constants.KEYSPACE_SUNBIRD, Constants.USER_ROLES,
                Map.of(Constants.USERID_KEY, userId), List.of(Constants.ROLE, Constants.SCOPE), userId
        );
        return records.stream()
                .map(record -> {
                    Object scopeObj = record.get(Constants.SCOPE);
                    List<Map<String, Object>> scopes = new ArrayList<>();
                    if (scopeObj instanceof List) {
                        scopes = (List<Map<String, Object>>) scopeObj;
                    } else if (scopeObj instanceof String scopeStr && !scopeStr.isBlank()) {
                        try {
                            scopes = mapper.readValue(scopeStr, new TypeReference<List<Map<String, Object>>>() {
                            });
                        } catch (Exception e) {
                            logger.warn("Failed to parse scope JSON for userId {}: {}", userId, e.getMessage());
                            return null;
                        }
                    }
                    if (!scopes.isEmpty() && scopes.stream().allMatch(scope -> rootOrgId.equals(scope.get(Constants.ORGANISATION_ID)))) {
                        return (String) record.get(Constants.ROLE);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private void mergeAndSortByIssuedDateOrTitle(List<Map<String, Object>> existingList, List<Map<String, Object>> newList) {
        List<Map<String, Object>> merged = Stream.concat(existingList.stream(), newList.stream())
                .sorted((a, b) -> {
                    OffsetDateTime dateA = parseOffsetDateTime(a.get(Constants.ISSUED_DATE));
                    OffsetDateTime dateB = parseOffsetDateTime(b.get(Constants.ISSUED_DATE));
                    if (dateA != null && dateB != null) {
                        return dateB.compareTo(dateA);
                    } else if (dateA == null && dateB == null) {
                        String titleA = (String) a.get(Constants.TITLE);
                        String titleB = (String) b.get(Constants.TITLE);
                        if (titleA == null && titleB == null) return 0;
                        if (titleA == null) return 1;
                        if (titleB == null) return -1;
                        return titleA.compareToIgnoreCase(titleB);
                    } else if (dateA == null) {
                        return 1;
                    } else {
                        return -1;
                    }
                })
                .toList();
        IntStream.range(0, merged.size()).forEach(i -> merged.get(i).put(Constants.INDEX, i));
        existingList.clear();
        existingList.addAll(merged);
    }

    private OffsetDateTime parseOffsetDateTime(Object dateObj) {
        if (dateObj instanceof String str && !str.isBlank()) {
            try {
                return OffsetDateTime.parse(str);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Updates additional fields for a user in an organization
     */
    @Override
    public ApiResponse updateAdditionalFields(Map<String, Object> request, String authToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.update.additionalFields");
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(authToken);

        if (StringUtils.isBlank(authToken)) {
            ProjectUtil.errorResponse(response, "Invalid or missing access token", HttpStatus.UNAUTHORIZED);
            return response;
        }

        String validationError = validateAdditionalFieldsRequest(request);
        if (validationError != null) {
            ProjectUtil.errorResponse(response, validationError, HttpStatus.BAD_REQUEST);
            return response;
        }

        String userId = (String) request.get(Constants.USER_ID);
        String organisationId = (String) request.get(Constants.ORGANISATION_ID);
        List<Map<String, Object>> customFieldValues = (List<Map<String, Object>>) request.get(Constants.CUSTOM_FIELD_VALUES);

        if (!StringUtils.equalsIgnoreCase(userIdFromToken, userId)) {
            ProjectUtil.errorResponse(response, "User ID in token does not match request", HttpStatus.UNAUTHORIZED);
            return response;
        }

        String contextType = Constants.ORG_ADDITIONAL_PROPERTIES;

        try {
            List<Map<String, Object>> existingData = getExistingContextData(userId, contextType);

            List<Map<String, Object>> restructuredData = restructureByOrgId(existingData, organisationId, customFieldValues);

            if (!saveContextData(userId, contextType, restructuredData)) {
                ProjectUtil.errorResponse(response, "Failed to save additional fields", HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            response.setResponseCode(HttpStatus.OK);
            response.put(Constants.RESPONSE, Constants.SUCCESS);
        } catch (Exception e) {
            logger.error("Error updating additional fields for userId: {}, orgId: {}", userId, organisationId, e);
            ProjectUtil.errorResponse(response, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    /**
     * Validates the request body for updating additional fields
     *
     * @param request The request body to validate
     * @return Error message if validation fails, null if validation passes
     */
    private String validateAdditionalFieldsRequest(Map<String, Object> request) {
        StringBuffer str = new StringBuffer();
        List<String> errList = new ArrayList<>();

        String userId = (String) request.get(Constants.USER_ID_RQST);
        if (StringUtils.isBlank(userId)) {
            errList.add(Constants.USER_ID_RQST);
        }

        String organisationId = (String) request.get(Constants.ORGANISATION_ID);
        if (StringUtils.isBlank(organisationId)) {
            errList.add(Constants.ORGANISATION_ID);
        }

        List<Map<String, Object>> customFieldValues = (List<Map<String, Object>>) request.get(Constants.CUSTOM_FIELD_VALUES);
        if (CollectionUtils.isEmpty(customFieldValues)) {
            errList.add(Constants.CUSTOM_FIELD_VALUES);
        }

        if (!errList.isEmpty()) {
            str.append(Constants.FAILED_DUE_TO_MISSING_PARAMS).append(errList).append(".");
            return str.toString();
        }

        for (Map<String, Object> field : customFieldValues) {
            String customFieldId = (String) field.get(Constants.CUSTOM_FIELD_ID);
            String fieldType = (String) field.get(Constants.FIELD_TYPE);

            if (StringUtils.isBlank(customFieldId)) {
                str.append("Each custom field must have a customFieldId. ");
                return str.toString();
            }

            if (StringUtils.isBlank(fieldType)) {
                str.append("Each custom field must have a type. ");
                return str.toString();
            }

            CustomFieldEntity customFieldEntity = getCustomFieldById(customFieldId);
            if (customFieldEntity == null) {
                str.append("Custom field with ID ").append(customFieldId).append(" does not exist. ");
                return str.toString();
            }

            if (!customFieldEntity.getIsActive()) {
                str.append("Custom field with ID ").append(customFieldId).append(" is not active. ");
                return str.toString();
            }

            String orgId = customFieldEntity.getCustomFieldData().get(Constants.ORGANISATION_ID).asText();
            if (!StringUtils.equals(orgId, organisationId)) {
                str.append("Custom field ").append(customFieldId)
                        .append(" is not configured for organization ").append(organisationId).append(". ");
                return str.toString();
            }

            String requestedAttributeName = (String) field.get(Constants.ATTRIBUTE_NAME);
            String actualAttributeName = customFieldEntity.getCustomFieldData().get(Constants.ATTRIBUTE_NAME).asText();
            if (!StringUtils.equals(requestedAttributeName, actualAttributeName)) {
                str.append("Invalid attribute name for custom field ").append(customFieldId).append(". ");
                return str.toString();
            }

            String storedType = customFieldEntity.getCustomFieldData().get(Constants.TYPE).asText();
            if (Constants.TEXT.equals(fieldType)) {
                if (field.get(Constants.VALUE) == null) {
                    str.append("Text field ").append(customFieldId).append(" must have a value. ");
                    return str.toString();
                }

                if (!Constants.TEXT.equals(storedType)) {
                    str.append("Custom field ").append(customFieldId).append(" is not of type text. ");
                    return str.toString();
                }
            } else if (Constants.MASTER_LIST.equals(fieldType)) {
                List<Map<String, Object>> values = (List<Map<String, Object>>) field.get(Constants.VALUES);
                if (CollectionUtils.isEmpty(values)) {
                    str.append("MasterList field ").append(customFieldId).append(" must have values. ");
                    return str.toString();
                }

                if (!Constants.MASTER_LIST.equals(storedType)) {
                    str.append("Custom field ").append(customFieldId).append(" is not of type masterList. ");
                    return str.toString();
                }

                String valueValidationError = validateMasterListValues(customFieldEntity, values);
                if (valueValidationError != null) {
                    str.append(valueValidationError);
                    return str.toString();
                }
            } else {
                str.append("Unsupported field type: ").append(fieldType).append(". ");
                return str.toString();
            }
        }
        return null;
    }

    /**
     * Validates the values for a masterList custom field
     *
     * @param entity          CustomFieldEntity containing the valid values
     * @param requestedValues Values from the request to validate
     * @return Error message if validation fails, null if validation passes
     */
    private String validateMasterListValues(CustomFieldEntity entity, List<Map<String, Object>> requestedValues) {
        try {
            JsonNode customFieldData = entity.getCustomFieldData().get(Constants.CUSTOM_FIELD_DATA);
            if (customFieldData == null || !customFieldData.isArray()) {
                return "Invalid master list field definition.";
            }

            // Check for duplicate levels - only one entry per level is allowed
            Map<Integer, Integer> levelCounts = new HashMap<>();
            for (Map<String, Object> value : requestedValues) {
                Integer level = (Integer) value.get(Constants.LEVEL);
                if (level == null) {
                    return "Each master list value must have a level.";
                }

                levelCounts.put(level, levelCounts.getOrDefault(level, 0) + 1);
                if (levelCounts.get(level) > 1) {
                    return "Only one value allowed per level. Found multiple entries at level " + level;
                }
            }

            // Sort values by level to validate parent-child relationships
            List<Map<String, Object>> sortedValues = requestedValues.stream()
                    .sorted(Comparator.comparing(map -> (Integer) map.get(Constants.LEVEL)))
                    .collect(Collectors.toList());

            // Track parent node for hierarchical validation
            JsonNode currentParentNode = null;

            // Validate each value in the hierarchy
            for (Map<String, Object> value : sortedValues) {
                String attributeName = (String) value.get(Constants.ATTRIBUTE_NAME);
                String valueStr = String.valueOf(value.get(Constants.VALUE));
                Integer level = (Integer) value.get(Constants.LEVEL);

                if (StringUtils.isBlank(attributeName) || valueStr == null || level == null) {
                    return "Each master list value must have attribute name, value and level.";
                }

                // For level 1, find matching node by value
                if (level == 1) {
                    currentParentNode = null;
                    for (JsonNode node : customFieldData) {
                        if (node.has(Constants.FIELD_VALUE) && valueStr.equals(node.get(Constants.FIELD_VALUE).asText())) {
                            currentParentNode = node;
                            break;
                        }
                    }

                    if (currentParentNode == null) {
                        return "Invalid value '" + valueStr + "' at level 1";
                    }
                }
                // For higher levels, find in children of current parent by value
                else if (currentParentNode != null) {
                    JsonNode childValues = currentParentNode.get(Constants.FIELD_VALUES);
                    JsonNode nextParent = null;

                    if (childValues != null && childValues.isArray()) {
                        for (JsonNode childNode : childValues) {
                            if (childNode.has(Constants.FIELD_VALUE) &&
                                    valueStr.equals(childNode.get(Constants.FIELD_VALUE).asText())) {
                                nextParent = childNode;
                                break;
                            }
                        }
                    }

                    if (nextParent == null) {
                        return "Invalid value '" + valueStr + "' at level " + level +
                                ". Not found under parent '" + currentParentNode.get(Constants.FIELD_VALUE).asText() + "'";
                    }

                    currentParentNode = nextParent;
                } else {
                    return "Invalid hierarchy structure. Parent node not found for level " + level;
                }
            }

            return null;
        } catch (Exception e) {
            logger.error("Error validating master list values: {}", e.getMessage());
            return "Error validating master list values.";
        }
    }

    /**
     * Restructures data to group by organization ID
     */
    private List<Map<String, Object>> restructureByOrgId(List<Map<String, Object>> existingData,
                                                         String currentOrgId,
                                                         List<Map<String, Object>> newCustomFieldValues) {

        Map<String, List<Map<String, Object>>> orgMap = new HashMap<>();
        for (Map<String, Object> item : existingData) {
            if (item.containsKey(Constants.ORGANISATION_ID) && item.containsKey(Constants.CUSTOM_FIELD_VALUES)) {
                String orgId = (String) item.get(Constants.ORGANISATION_ID);
                orgMap.put(orgId, (List<Map<String, Object>>) item.get(Constants.CUSTOM_FIELD_VALUES));
            } else if (item.containsKey(Constants.ORGANISATION_ID)) {
                String orgId = (String) item.get(Constants.ORGANISATION_ID);
                orgMap.computeIfAbsent(orgId, k -> new ArrayList<>()).add(item);
            }
        }
        orgMap.put(currentOrgId, newCustomFieldValues);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : orgMap.entrySet()) {
            Map<String, Object> orgData = new HashMap<>();
            orgData.put(Constants.ORGANISATION_ID, entry.getKey());
            orgData.put(Constants.CUSTOM_FIELD_VALUES, entry.getValue());
            result.add(orgData);
        }
        return result;
    }

    /**
     * Retrieves a custom field from PostgreSQL by its ID
     *
     * @param customFieldId ID of the custom field to retrieve
     * @return CustomFieldEntity if found, null otherwise
     */
    private CustomFieldEntity getCustomFieldById(String customFieldId) {
        try {
            return customFieldRepository.findByCustomFiledIdAndIsActiveTrue(customFieldId).orElse(null);
        } catch (Exception e) {
            logger.error("Error retrieving custom field with ID {}: {}", customFieldId, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public ApiResponse getAdditionalFieldsByOrg(String userId, String orgId, String authToken) {
        ApiResponse response = ProjectUtil.createDefaultResponse("api.get.additionalFieldsByOrg");
        String userIdFromToken = accessTokenValidator.fetchUserIdFromAccessToken(authToken);

        if (StringUtils.isBlank(authToken)) {
            ProjectUtil.errorResponse(response, "Invalid or missing access token", HttpStatus.UNAUTHORIZED);
            return response;
        }

        if (!StringUtils.equalsIgnoreCase(userIdFromToken, userId)) {
            ProjectUtil.errorResponse(response, "User ID in token does not match request", HttpStatus.UNAUTHORIZED);
            return response;
        }

        try {
            String contextType = Constants.ORG_ADDITIONAL_PROPERTIES;
            List<Map<String, Object>> dataList = getExistingContextData(userId, contextType);

            // Find data for the specified organization
            Map<String, Object> orgData = null;
            for (Map<String, Object> item : dataList) {
                String itemOrgId = (String) item.get(Constants.ORGANISATION_ID);
                if (orgId.equals(itemOrgId)) {
                    orgData = item;
                    break;
                }
            }

            if (MapUtils.isEmpty(orgData)) {
                response.setResponseCode(HttpStatus.OK);
                response.put(Constants.RESPONSE, Collections.emptyMap());
                return response;
            }
            response.setResponseCode(HttpStatus.OK);
            response.put(Constants.RESPONSE, orgData);
            return response;
        } catch (Exception e) {
            logger.error("Error retrieving additional fields for userId: {} and orgId: {}", userId, orgId, e);
            ProjectUtil.errorResponse(response, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);
            return response;
        }
    }
}
