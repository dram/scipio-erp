/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.entityext.cache;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.cache.UtilCache;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntity;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericPK;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.DistributedCacheClear;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.entityext.EntityServiceFactory;
import org.ofbiz.security.Security;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

/**
 * Entity Engine Cache Services
 */
public class EntityCacheServices implements DistributedCacheClear {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    private static final Map<String, List<String>> CACHING_EXCLUDE_NAMES = Collections.unmodifiableMap(UtilMisc.toMap(
            "system-essential", UtilMisc.unmodifiableArrayList(
                    "service.ModelServiceMapByModel", "service.ServiceConfig", "service.ServiceECAs",
                    "service.ServiceGroups", "service.ModelServiceMapByDispatcher")
    ));
    private static final Map<String, List<Pattern>> CACHING_EXCLUDE_NAMES_PATTERNS = Collections.unmodifiableMap(UtilMisc.toMap(
    ));

    protected Delegator delegator = null;
    protected LocalDispatcher dispatcher = null;
    protected String userLoginId = null;

    public EntityCacheServices() {}

    public void setDelegator(Delegator delegator, String userLoginId) {
        this.delegator = delegator;
        this.dispatcher = EntityServiceFactory.getLocalDispatcher(delegator);
        this.userLoginId = userLoginId;
    }

    public GenericValue getAuthUserLogin() {
        GenericValue userLogin = null;
        try {
            userLogin = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", userLoginId).cache().queryOne();
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error finding the userLogin for distributed cache clear", module);
        }
        return userLogin;
    }

    public void distributedClearCacheLine(GenericValue value) {
        // Debug.logInfo("running distributedClearCacheLine for value: " + value, module);
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed cache clear", module);
            return;
        }

        GenericValue userLogin = getAuthUserLogin();
        if (userLogin == null) {
            Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
            return;
        }

        try {
            this.dispatcher.runAsync("distributedClearCacheLineByValue", UtilMisc.toMap("value", value, "userLogin", userLogin), false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running the distributedClearCacheLineByValue service", module);
        }
    }

    public void distributedClearCacheLineFlexible(GenericEntity dummyPK) {
        // Debug.logInfo("running distributedClearCacheLineFlexible for dummyPK: " + dummyPK, module);
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed cache clear", module);
            return;
        }

        GenericValue userLogin = getAuthUserLogin();
        if (userLogin == null) {
            Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
            return;
        }

        try {
            this.dispatcher.runAsync("distributedClearCacheLineByDummyPK", UtilMisc.toMap("dummyPK", dummyPK, "userLogin", userLogin), false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running the distributedClearCacheLineByDummyPK service", module);
        }
    }

    public void distributedClearCacheLineByCondition(String entityName, EntityCondition condition) {
        // Debug.logInfo("running distributedClearCacheLineByCondition for (name, condition): " + entityName + ", " + condition + ")", module);
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed cache clear", module);
            return;
        }

        GenericValue userLogin = getAuthUserLogin();
        if (userLogin == null) {
            Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
            return;
        }

        try {
            this.dispatcher.runAsync("distributedClearCacheLineByCondition", UtilMisc.toMap("entityName", entityName, "condition", condition, "userLogin", userLogin), false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running the distributedClearCacheLineByCondition service", module);
        }
    }

    public void distributedClearCacheLine(GenericPK primaryKey) {
        // Debug.logInfo("running distributedClearCacheLine for primaryKey: " + primaryKey, module);
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed cache clear", module);
            return;
        }

        GenericValue userLogin = getAuthUserLogin();
        if (userLogin == null) {
            Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
            return;
        }

        try {
            this.dispatcher.runAsync("distributedClearCacheLineByPrimaryKey", UtilMisc.toMap("primaryKey", primaryKey, "userLogin", userLogin), false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running the distributedClearCacheLineByPrimaryKey service", module);
        }
    }

    public void clearAllCaches() {
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed clear all caches", module);
            return;
        }

        GenericValue userLogin = getAuthUserLogin();
        if (userLogin == null) {
            Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
            return;
        }

        try {
            this.dispatcher.runAsync("distributedClearAllEntityCaches", UtilMisc.toMap("userLogin", userLogin), false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running the distributedClearAllCaches service", module);
        }
    }

    /**
     * Clear All Entity Caches Service
     * @param dctx The DispatchContext that this service is operating in
     * @param context Map containing the input parameters
     * @return Map with the result of the service, the output parameters
     */
    public static Map<String, Object> clearAllEntityCaches(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Boolean distributeBool = (Boolean) context.get("distribute");
        boolean distribute = false;
        if (distributeBool != null) distribute = distributeBool;

        delegator.clearAllCaches(distribute);

        return ServiceUtil.returnSuccess();
    }

    /**
     * Clear Cache Line Service: one of the following context parameters is required: value, dummyPK or primaryKey
     * @param dctx The DispatchContext that this service is operating in
     * @param context Map containing the input parameters
     * @return Map with the result of the service, the output parameters
     */
    public static Map<String, Object> clearCacheLine(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Boolean distributeBool = (Boolean) context.get("distribute");
        boolean distribute = false;
        if (distributeBool != null) distribute = distributeBool;

        if (context.containsKey("value")) {
            GenericValue value = (GenericValue) context.get("value");
            if (Debug.infoOn()) Debug.logInfo("Got a clear cache line by value service call; entityName: " + value.getEntityName(), module);
            if (Debug.verboseOn()) Debug.logVerbose("Got a clear cache line by value service call; value: " + value, module);
            delegator.clearCacheLine(value, distribute);
        } else if (context.containsKey("dummyPK")) {
            GenericEntity dummyPK = (GenericEntity) context.get("dummyPK");
            if (Debug.infoOn()) Debug.logInfo("Got a clear cache line by dummyPK service call; entityName: " + dummyPK.getEntityName(), module);
            if (Debug.verboseOn()) Debug.logVerbose("Got a clear cache line by dummyPK service call; dummyPK: " + dummyPK, module);
            delegator.clearCacheLineFlexible(dummyPK, distribute);
        } else if (context.containsKey("primaryKey")) {
            GenericPK primaryKey = (GenericPK) context.get("primaryKey");
            if (Debug.infoOn()) Debug.logInfo("Got a clear cache line by primaryKey service call; entityName: " + primaryKey.getEntityName(), module);
            if (Debug.verboseOn()) Debug.logVerbose("Got a clear cache line by primaryKey service call; primaryKey: " + primaryKey, module);
            delegator.clearCacheLine(primaryKey, distribute);
        } else if (context.containsKey("condition")) {
            String entityName = (String) context.get("entityName");
            EntityCondition condition = (EntityCondition) context.get("condition");
            if (Debug.infoOn()) Debug.logInfo("Got a clear cache line by condition service call; entityName: " + entityName, module);
            if (Debug.verboseOn()) Debug.logVerbose("Got a clear cache line by condition service call; condition: " + condition, module);
            delegator.clearCacheLineByCondition(entityName, condition, distribute);
        }
        return ServiceUtil.returnSuccess();
    }

    /**
     * Clears all system cache (equivalent to UtilCacheEvents.clearAllEvent).
     * <p>TODO: refactor as class so clients could override CACHING_EXCLUDE_NAMES/CACHING_EXCLUDE_NAMES_PATTERNS.</p>
     * <p>SCIPIO: 2020-03-10: Added.</p>
     */
    public static Map<String, Object> clearAllUtilCaches(DispatchContext dctx, Map<String, ? extends Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        try {
            Collection<String> excludeNames = UtilMisc.asCollectionNonNull(context.get("excludeNames"));
            Collection<Pattern> excludePatterns = UtilMisc.getPatterns(context.get("excludePatterns"));
            Collection<String> excludeTypes = !"none".equals(context.get("excludeTypes")) ?
                    UtilMisc.asCollectionNonNull(context.get("excludeTypes")) : null;
            if (excludeTypes != null) {
                excludeNames = (excludeNames != null) ? new ArrayList<>(excludeNames) : new ArrayList<>();
                for(String excludeType : excludeTypes) {
                    Collection<String> excludeNamesForType = CACHING_EXCLUDE_NAMES.get(excludeType);
                    if (excludeNamesForType != null) {
                        excludeNames.addAll(excludeNamesForType);
                    }
                    Collection<Pattern> excludeNamesPatternsForType = CACHING_EXCLUDE_NAMES_PATTERNS.get(excludeType);
                    if (excludeNamesPatternsForType != null) {
                        excludePatterns.addAll(excludeNamesPatternsForType);
                    }
                }
            }

            Security security = dctx.getSecurity();
            if (!security.hasPermission("UTIL_CACHE_EDIT", userLogin)) {
                String errMsg = UtilProperties.getMessage("WebtoolsErrorUiLabels", "utilCacheEvents.permissionEdit", locale) + ".";
                return ServiceUtil.returnError("Error - cache could not be cleared: " + errMsg);
            }

            UtilCache.clearAllCaches(excludeNames, excludePatterns);

            if (Boolean.TRUE.equals(context.get("distribute"))) {
                DistributedCacheClear dcc = delegator.getDistributedCacheClear();
                if (dcc != null) {
                    dcc.clearAllUtilCaches(UtilMisc.toMap("excludeNames", excludeNames, "excludePatterns", excludePatterns,
                            "excludeTypes", "none"));
                }
            }

            return ServiceUtil.returnSuccess();
        } catch(Exception e) {
            return ServiceUtil.returnError("Error - cache could not be cleared: " + e.getMessage());
        }
    }

    @Override
    public void clearAllUtilCaches(Map<String, Object> context) { // SCIPIO
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed clear all caches", module);
            return;
        }
        if (context == null) {
            context = new HashMap<>();
        }

        GenericValue contextUserLogin = (GenericValue) context.get("userLogin");
        if (contextUserLogin == null) {
            GenericValue userLogin = getAuthUserLogin();
            if (userLogin == null) {
                Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
                return;
            }
            context.put("userLogin", userLogin);
        }

        try {
            this.dispatcher.runAsync("distributedClearAllUtilCaches", context, false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running the distributedClearAllUtilCaches service", module);
        }
    }

    @Override
    public void runDistributedService(String serviceName, Map<String, Object> context) {
        if (this.dispatcher == null) {
            Debug.logWarning("No dispatcher is available, somehow the setDelegator (which also creates a dispatcher) was not called, not running distributed clear all caches", module);
            return;
        }

        GenericValue contextUserLogin = (GenericValue) context.get("userLogin");
        if (contextUserLogin == null) {
            GenericValue userLogin = getAuthUserLogin();
            if (userLogin == null) {
                Debug.logWarning("The userLogin for distributed cache clear was not found with userLoginId [" + userLoginId + "], not clearing remote caches.", module);
                return;
            }
            context.put("userLogin", userLogin);
        }

        try {
            this.dispatcher.runAsync(serviceName, context, false);
        } catch (GenericServiceException e) {
            Debug.logError(e, "Error running distributed service [" + serviceName + "]", module);
        }
    }
}
