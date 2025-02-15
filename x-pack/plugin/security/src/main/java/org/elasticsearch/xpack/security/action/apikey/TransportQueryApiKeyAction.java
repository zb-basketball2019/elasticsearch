/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.action.apikey;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.security.SecurityContext;
import org.elasticsearch.xpack.core.security.action.apikey.QueryApiKeyAction;
import org.elasticsearch.xpack.core.security.action.apikey.QueryApiKeyRequest;
import org.elasticsearch.xpack.core.security.action.apikey.QueryApiKeyResponse;
import org.elasticsearch.xpack.core.security.authc.Authentication;
import org.elasticsearch.xpack.security.authc.ApiKeyService;
import org.elasticsearch.xpack.security.support.ApiKeyBoolQueryBuilder;
import org.elasticsearch.xpack.security.support.ApiKeyFieldNameTranslators;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.elasticsearch.xpack.security.support.SecuritySystemIndices.SECURITY_MAIN_ALIAS;

public final class TransportQueryApiKeyAction extends TransportAction<QueryApiKeyRequest, QueryApiKeyResponse> {

    // API keys with no "type" field are implicitly of type "rest" (this is the case for all API Keys created before v8.9).
    // The below runtime field ensures that the "type" field can be used by the {@link RestQueryApiKeyAction},
    // while making the implicit "rest" type feature transparent to the caller (hence all keys are either "rest"
    // or "cross_cluster", and the "type" is always set).
    // This can be improved, to get rid of the runtime performance impact of the runtime field, by reindexing
    // the api key docs and setting the "type" to "rest" if empty. But the infrastructure to run such a maintenance
    // task on a system index (once the cluster version permits) is not currently available.
    public static final String API_KEY_TYPE_RUNTIME_MAPPING_FIELD = "runtime_key_type";
    private static final Map<String, Object> API_KEY_TYPE_RUNTIME_MAPPING = Map.of(
        API_KEY_TYPE_RUNTIME_MAPPING_FIELD,
        Map.of("type", "keyword", "script", Map.of("source", "emit(field('type').get(\"rest\"));"))
    );

    private final ApiKeyService apiKeyService;
    private final SecurityContext securityContext;

    @Inject
    public TransportQueryApiKeyAction(
        TransportService transportService,
        ActionFilters actionFilters,
        ApiKeyService apiKeyService,
        SecurityContext context
    ) {
        super(QueryApiKeyAction.NAME, actionFilters, transportService.getTaskManager());
        this.apiKeyService = apiKeyService;
        this.securityContext = context;
    }

    @Override
    protected void doExecute(Task task, QueryApiKeyRequest request, ActionListener<QueryApiKeyResponse> listener) {
        final Authentication authentication = securityContext.getAuthentication();
        if (authentication == null) {
            listener.onFailure(new IllegalStateException("authentication is required"));
        }

        final SearchSourceBuilder searchSourceBuilder = SearchSourceBuilder.searchSource()
            .version(false)
            .fetchSource(true)
            .trackTotalHits(true);

        if (request.getFrom() != null) {
            searchSourceBuilder.from(request.getFrom());
        }
        if (request.getSize() != null) {
            searchSourceBuilder.size(request.getSize());
        }

        final AtomicBoolean accessesApiKeyTypeField = new AtomicBoolean(false);
        searchSourceBuilder.query(ApiKeyBoolQueryBuilder.build(request.getQueryBuilder(), fieldName -> {
            if (API_KEY_TYPE_RUNTIME_MAPPING_FIELD.equals(fieldName)) {
                accessesApiKeyTypeField.set(true);
            }
        }, request.isFilterForCurrentUser() ? authentication : null));

        if (request.getFieldSortBuilders() != null) {
            translateFieldSortBuilders(request.getFieldSortBuilders(), searchSourceBuilder, fieldName -> {
                if (API_KEY_TYPE_RUNTIME_MAPPING_FIELD.equals(fieldName)) {
                    accessesApiKeyTypeField.set(true);
                }
            });
        }

        // only add the query-level runtime field to the search request if it's actually referring the "type" field
        if (accessesApiKeyTypeField.get()) {
            searchSourceBuilder.runtimeMappings(API_KEY_TYPE_RUNTIME_MAPPING);
        }

        if (request.getSearchAfterBuilder() != null) {
            searchSourceBuilder.searchAfter(request.getSearchAfterBuilder().getSortValues());
        }

        final SearchRequest searchRequest = new SearchRequest(new String[] { SECURITY_MAIN_ALIAS }, searchSourceBuilder);
        apiKeyService.queryApiKeys(searchRequest, request.withLimitedBy(), listener);
    }

    // package private for testing
    static void translateFieldSortBuilders(
        List<FieldSortBuilder> fieldSortBuilders,
        SearchSourceBuilder searchSourceBuilder,
        Consumer<String> fieldNameVisitor
    ) {
        fieldSortBuilders.forEach(fieldSortBuilder -> {
            if (fieldSortBuilder.getNestedSort() != null) {
                throw new IllegalArgumentException("nested sorting is not supported for API Key query");
            }
            if (FieldSortBuilder.DOC_FIELD_NAME.equals(fieldSortBuilder.getFieldName())) {
                searchSourceBuilder.sort(fieldSortBuilder);
            } else {
                final String translatedFieldName = ApiKeyFieldNameTranslators.translate(fieldSortBuilder.getFieldName());
                fieldNameVisitor.accept(translatedFieldName);
                if (translatedFieldName.equals(fieldSortBuilder.getFieldName())) {
                    searchSourceBuilder.sort(fieldSortBuilder);
                } else {
                    final FieldSortBuilder translatedFieldSortBuilder = new FieldSortBuilder(translatedFieldName).order(
                        fieldSortBuilder.order()
                    )
                        .missing(fieldSortBuilder.missing())
                        .unmappedType(fieldSortBuilder.unmappedType())
                        .setFormat(fieldSortBuilder.getFormat());

                    if (fieldSortBuilder.sortMode() != null) {
                        translatedFieldSortBuilder.sortMode(fieldSortBuilder.sortMode());
                    }
                    if (fieldSortBuilder.getNestedSort() != null) {
                        translatedFieldSortBuilder.setNestedSort(fieldSortBuilder.getNestedSort());
                    }
                    if (fieldSortBuilder.getNumericType() != null) {
                        translatedFieldSortBuilder.setNumericType(fieldSortBuilder.getNumericType());
                    }
                    searchSourceBuilder.sort(translatedFieldSortBuilder);
                }
            }
        });
    }
}
