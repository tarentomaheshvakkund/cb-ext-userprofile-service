package com.igot.cb.masterdata.controller;

import com.igot.cb.masterdata.service.MasterDataService;
import com.igot.cb.util.ApiResponse;
import com.igot.cb.util.Constants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Author: mahesh.vakkund
 */
@RestController
@RequestMapping("/v1/masterdata")
public class MasterDataController {

    @Autowired
    MasterDataService masterDataService;

    @GetMapping(value = "/list/institutions")
    public ResponseEntity<ApiResponse> getInstitutionsList(@RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        return new ResponseEntity<>(masterDataService.getInstitutionsList(authToken), HttpStatus.OK);
    }

    @GetMapping(value = "/list/degrees")
    public ResponseEntity<ApiResponse> getDegreesList(@RequestHeader(Constants.X_AUTH_TOKEN) String authToken) {
        return new ResponseEntity<>(masterDataService.getDegreesList(authToken), HttpStatus.OK);
    }

    @PostMapping(value = "/update/institution")
    public ResponseEntity<ApiResponse> updateInstitution(
            @RequestHeader(Constants.X_AUTH_TOKEN) String authToken,
            @RequestBody Map<String, Object> requestBody) {
        return new ResponseEntity<>(masterDataService.updateInstitutionList(authToken, requestBody), HttpStatus.OK);
    }

    @PostMapping(value = "/update/degree")
    public ResponseEntity<ApiResponse> updateDegree(
            @RequestHeader(Constants.X_AUTH_TOKEN) String authToken,
            @RequestBody Map<String, Object> requestBody) {
        return new ResponseEntity<>(masterDataService.updateDegreesList(authToken, requestBody), HttpStatus.OK);
    }

}