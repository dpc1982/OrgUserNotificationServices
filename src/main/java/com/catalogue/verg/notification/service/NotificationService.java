package com.catalogue.verg.notification.service;

import com.catalogue.verg.core.dto.CustomResponse;
import com.catalogue.verg.core.dto.NotificationRequest;
import com.catalogue.verg.core.elasticsearch.dto.SearchCriteria;
import com.catalogue.verg.core.elasticsearch.dto.SearchResult;
import com.catalogue.verg.notification.entity.Notification;
import com.catalogue.verg.notification.entity.NotificationTemplate;
import com.catalogue.verg.notification.repository.NotificationRepository;
import com.catalogue.verg.notification.repository.NotificationTemplateRepository;
import com.catalogue.verg.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTemplateRepository templateRepository;
    private final UserService userService;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationTemplateRepository templateRepository,
            UserService userService) {

        this.notificationRepository = notificationRepository;
        this.templateRepository = templateRepository;
        this.userService = userService;
    }

    @Transactional
    public CustomResponse sendNotification(
            NotificationRequest request) {

        CustomResponse response = new CustomResponse();

        if (request.getTemplateCode() == null
                || request.getTemplateCode().trim().isEmpty()) {

            response.setMessage("Template code is required");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (request.getTemplateModule() == null
                || request.getTemplateModule().trim().isEmpty()) {

            response.setMessage("Template module is required");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (request.getNotificationChannel() == null
                || request.getNotificationChannel().trim().isEmpty()) {

            response.setMessage("Notification channel is required");
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (!"PORTAL".equalsIgnoreCase(
                request.getNotificationChannel().trim())) {

            response.setMessage(
                    "Currently only PORTAL notification channel is supported"
            );
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        NotificationTemplate template =
                templateRepository
                        .findByTemplateCodeAndIsDeletedFalse(
                                request.getTemplateCode().trim()
                        )
                        .orElse(null);

        if (template == null) {

            response.setMessage("Notification template not found");
            response.setResponseCode(HttpStatus.NOT_FOUND);
            return response;
        }

        if (template.getTemplateModule() == null
                || !template.getTemplateModule()
                .equalsIgnoreCase(
                        request.getTemplateModule().trim()
                )) {

            response.setMessage(
                    "Template module does not match the template"
            );
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (template.getNotificationChannel() == null
                || !template.getNotificationChannel()
                .equalsIgnoreCase(
                        request.getNotificationChannel().trim()
                )) {

            response.setMessage(
                    "Notification channel does not match the template"
            );
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        if (template.getReceiver() == null
                || template.getReceiver().trim().isEmpty()) {

            response.setMessage(
                    "Receiver is not configured in the notification template"
            );
            response.setResponseCode(HttpStatus.BAD_REQUEST);
            return response;
        }

        String receiverEntityType =
                template.getReceiver().trim();

        String messageBody =
                replaceTemplateVariables(
                        template.getTemplateContent(),
                        request.getTemplateVariables()
                );

        SearchCriteria countSearchCriteria =
                buildReceiverSearchCriteria(
                        receiverEntityType,
                        0,
                        1
                );

        CustomResponse countResponse =
                userService.searchUser(
                        countSearchCriteria
                );

        if (countResponse == null
                || countResponse.getResult() == null) {

            response.setMessage(
                    "Unable to find users for receiver: "
                            + receiverEntityType
            );
            response.setResponseCode(
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
            return response;
        }

        SearchResult countSearchResult =
                extractSearchResult(countResponse);

        if (countSearchResult == null) {

            response.setMessage(
                    "Invalid response received while searching users"
            );
            response.setResponseCode(
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
            return response;
        }

        long totalCount =
                countSearchResult.getTotalCount();

        if (totalCount <= 0) {

            response.setMessage(
                    "No users found for receiver: "
                            + receiverEntityType
            );
            response.setResponseCode(
                    HttpStatus.NOT_FOUND
            );
            return response;
        }

        SearchCriteria allUsersSearchCriteria =
                buildReceiverSearchCriteria(
                        receiverEntityType,
                        0,
                        (int) totalCount
                );

        CustomResponse allUsersResponse =
                userService.searchUser(
                        allUsersSearchCriteria
                );

        if (allUsersResponse == null
                || allUsersResponse.getResult() == null) {

            response.setMessage(
                    "Unable to fetch users for receiver: "
                            + receiverEntityType
            );
            response.setResponseCode(
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
            return response;
        }

        SearchResult allUsersSearchResult =
                extractSearchResult(allUsersResponse);

        if (allUsersSearchResult == null) {

            response.setMessage(
                    "Invalid response received while fetching users"
            );
            response.setResponseCode(
                    HttpStatus.INTERNAL_SERVER_ERROR
            );
            return response;
        }

        JsonNode data =
                allUsersSearchResult.getData();

        if (data == null || !data.isArray() || data.isEmpty()) {

            response.setMessage(
                    "No users found for receiver: "
                            + receiverEntityType
            );
            response.setResponseCode(
                    HttpStatus.NOT_FOUND
            );
            return response;
        }

        List<Notification> savedNotifications =
                new ArrayList<>();

        LocalDateTime now =
                LocalDateTime.now();

        for (JsonNode user : data) {

            if (!user.hasNonNull("id")
                    || user.get("id").asText().trim().isEmpty()) {
                continue;
            }

            String userId =
                    user.get("id").asText().trim();

            Notification notification =
                    new Notification();

            notification.setUserId(userId);

            if (user.hasNonNull("email")) {
                notification.setEmailId(
                        user.get("email").asText()
                );
            }

            if (user.hasNonNull("phoneNumber")) {
                notification.setPhoneNo(
                        user.get("phoneNumber").asText()
                );
            }

            notification.setSubject(
                    template.getSubject()
            );

            notification.setMessageBody(
                    messageBody
            );

            notification.setNotificationType(
                    "PORTAL"
            );

            notification.setTemplateId(
                    template.getId()
            );

            notification.setTemplateModule(
                    template.getTemplateModule()
            );

            notification.setStatus("SENT");
            notification.setMarkedAsRead(false);
            notification.setCleared(false);
            notification.setCreatedAt(now);
            notification.setUpdatedAt(now);
            notification.setSentAt(now);

            Notification savedNotification =
                    notificationRepository.save(
                            notification
                    );

            savedNotifications.add(
                    savedNotification
            );
        }

        if (savedNotifications.isEmpty()) {

            response.setMessage(
                    "No valid users found for receiver: "
                            + receiverEntityType
            );
            response.setResponseCode(
                    HttpStatus.NOT_FOUND
            );
            return response;
        }

        response.setMessage(
                "Notification sent successfully to "
                        + savedNotifications.size()
                        + " user(s)"
        );

        response.setResponseCode(
                HttpStatus.CREATED
        );

        response.getResult().put(
                "notifications",
                savedNotifications
        );

        response.getResult().put(
                "receiver",
                receiverEntityType
        );

        response.getResult().put(
                "totalRecipients",
                savedNotifications.size()
        );

        return response;
    }

    private SearchCriteria buildReceiverSearchCriteria(
            String receiver,
            int pageNumber,
            int pageSize) {

        HashMap<String, Object> filterCriteriaMap =
                new HashMap<>();

        filterCriteriaMap.put(
                "entityType",
                receiver
        );

        SearchCriteria searchCriteria =
                new SearchCriteria();

        searchCriteria.setFilterCriteriaMap(
                filterCriteriaMap
        );

        searchCriteria.setPageNumber(
                pageNumber
        );

        searchCriteria.setPageSize(
                pageSize
        );

        searchCriteria.setOrderBy(
                "firstName"
        );

        searchCriteria.setOrderDirection(
                "asc"
        );

        searchCriteria.setFacets(
                List.of("entityType")
        );

        return searchCriteria;
    }

    private SearchResult extractSearchResult(
            CustomResponse response) {

        Object result =
                response.getResult().get("result");

        if (!(result instanceof SearchResult)) {
            return null;
        }

        return (SearchResult) result;
    }

    private String replaceTemplateVariables(
            String templateContent,
            JsonNode templateVariables) {

        if (templateContent == null
                || templateVariables == null
                || templateVariables.isNull()) {

            return templateContent;
        }

        String message =
                templateContent;

        Iterator<Map.Entry<String, JsonNode>>
                fields =
                templateVariables.fields();

        while (fields.hasNext()) {

            Map.Entry<String, JsonNode>
                    field =
                    fields.next();

            String variableName =
                    field.getKey();

            String variableValue =
                    field.getValue().isNull()
                            ? ""
                            : field.getValue().asText();

            message = message.replace(
                    "{{" + variableName + "}}",
                    variableValue
            );
        }

        return message;
    }

    @Transactional(readOnly = true)
    public CustomResponse getPortalNotifications(
            String userId,
            String filter,
            int page,
            int size) {

        CustomResponse response =
                new CustomResponse();

        if (userId == null
                || userId.trim().isEmpty()) {

            response.setMessage(
                    "User ID is required"
            );
            response.setResponseCode(
                    HttpStatus.BAD_REQUEST
            );
            return response;
        }

        if (page < 0) {

            response.setMessage(
                    "Page cannot be less than 0"
            );
            response.setResponseCode(
                    HttpStatus.BAD_REQUEST
            );
            return response;
        }

        if (size <= 0) {

            response.setMessage(
                    "Page size must be greater than 0"
            );
            response.setResponseCode(
                    HttpStatus.BAD_REQUEST
            );
            return response;
        }

        String notificationFilter =
                filter == null || filter.trim().isEmpty()
                        ? "ALL"
                        : filter.trim().toUpperCase();

        if (!notificationFilter.equals("ALL")
                && !notificationFilter.equals("READ")
                && !notificationFilter.equals("UNREAD")) {

            response.setMessage(
                    "Invalid filter. Allowed values are ALL, READ and UNREAD"
            );
            response.setResponseCode(
                    HttpStatus.BAD_REQUEST
            );
            return response;
        }

        Pageable pageable =
                PageRequest.of(
                        page,
                        size
                );

        Page<Notification> notificationPage;

        if ("ALL".equals(notificationFilter)) {

            notificationPage =
                    notificationRepository.findPortalNotifications(
                            userId.trim(),
                            pageable
                    );

        } else {

            boolean readStatus =
                    "READ".equals(notificationFilter);

            notificationPage =
                    notificationRepository
                            .findPortalNotificationsByReadStatus(
                                    userId.trim(),
                                    readStatus,
                                    pageable
                            );
        }

        response.setMessage(
                "Portal notifications fetched successfully"
        );

        response.setResponseCode(
                HttpStatus.OK
        );

        response.getResult().put(
                "notifications",
                notificationPage.getContent()
        );

        response.getResult().put(
                "currentPage",
                notificationPage.getNumber()
        );

        response.getResult().put(
                "pageSize",
                notificationPage.getSize()
        );

        response.getResult().put(
                "totalElements",
                notificationPage.getTotalElements()
        );

        response.getResult().put(
                "totalPages",
                notificationPage.getTotalPages()
        );

        response.getResult().put(
                "first",
                notificationPage.isFirst()
        );

        response.getResult().put(
                "last",
                notificationPage.isLast()
        );

        return response;
    }
}
