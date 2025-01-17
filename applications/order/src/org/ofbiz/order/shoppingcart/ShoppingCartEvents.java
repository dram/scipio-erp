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
package org.ofbiz.order.shoppingcart;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.ObjectType;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilNumber;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericPK;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.order.shoppingcart.product.ProductPromoWorker;
import org.ofbiz.order.shoppinglist.ShoppingListWorker;
import org.ofbiz.product.catalog.CatalogWorker;
import org.ofbiz.product.config.ProductConfigWorker;
import org.ofbiz.product.config.ProductConfigWrapper;
import org.ofbiz.product.product.ProductWorker;
import org.ofbiz.product.store.ProductStoreSurveyWrapper;
import org.ofbiz.product.store.ProductStoreWorker;
import org.ofbiz.security.Security;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ModelService;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.webapp.control.RequestAttrPolicy.RequestAttrNamePolicy;
import org.ofbiz.webapp.control.RequestAttrPolicy.RequestSavingAttrPolicy;
import org.ofbiz.webapp.control.RequestHandler;
import org.ofbiz.webapp.control.RequestVarScopes;
import org.ofbiz.webapp.event.EventUtil;

/**
 * Shopping cart events.
 * <p>
 * SCIPIO: NOTE: 2018-11-22: Events which modify the shopping cart stored in session ("shoppingCart") 
 * must wrap its update code in a {@link CartUpdate} section.
 */
public class ShoppingCartEvents {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());
    public static final String resource = "OrderUiLabels";
    public static final String resource_error = "OrderErrorUiLabels";

    public static final String product_resource_error = "ProductErrorUiLabels"; // SCIPIO: new

    private static final String NO_ERROR = "noerror";
    private static final String NON_CRITICAL_ERROR = "noncritical";
    private static final String ERROR = "error";

    public static final MathContext generalRounding = new MathContext(10);

    public static String addProductPromoCode(HttpServletRequest request, HttpServletResponse response) {
        Locale locale = UtilHttp.getLocale(request);
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String productPromoCodeId = request.getParameter("productPromoCodeId");
        if (UtilValidate.isNotEmpty(productPromoCodeId)) {
            try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
            ShoppingCart cart = cartUpdate.getCartForUpdate();
            
            String checkResult = cart.addProductPromoCode(productPromoCodeId, dispatcher);
            if (UtilValidate.isNotEmpty(checkResult)) {
                request.setAttribute("_ERROR_MESSAGE_", checkResult);
                return "error";
            }
            
            cartUpdate.commit(cart);
            }
            request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource, "OrderPromoAppliedSuccessfully", UtilMisc.toMap("productPromoCodeId", productPromoCodeId), locale));
        }
        return "success";
    }

    public static String removePromotion(HttpServletRequest request,HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String promoCodeId = request.getParameter("promoCode");
        String result = "error";

        if (!promoCodeId.isEmpty()) {
            try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
            ShoppingCart cart = cartUpdate.getCartForUpdate();

            cart.getProductPromoCodesEntered().clear();
            GenericValue productPromoCode = null;
            try {
                productPromoCode = dispatcher.getDelegator().findOne("ProductPromoCode", UtilMisc.toMap("productPromoCodeId", promoCodeId), false);
                if (!productPromoCode.isEmpty()) {
                    String productPromoId = productPromoCode.getString("productPromoId");
                    GenericValue productPromoAction = null;
                    Map<String, String> productPromoActionMap = new HashMap<>();
                    productPromoActionMap.put("productPromoId", productPromoId);
                    productPromoActionMap.put("productPromoRuleId", "01");
                    productPromoActionMap.put("productPromoActionSeqId", "01");

                    productPromoAction = dispatcher.getDelegator().findOne("ProductPromoAction", productPromoActionMap, false);
                    if (!productPromoAction.isEmpty()) {
                        int index = cart.getAdjustmentPromoIndex(productPromoId);
                        /*Remove order adjustment*/
                        if (index != -1) {
                            cart.removeAdjustment(index);
                            result = "success";
                        }

                        /*Remove product adjustment*/
                        for (ShoppingCartItem checkItem : cart) {
                            List<GenericValue> itemAdjustments = checkItem.getAdjustments();
                            if (!itemAdjustments.isEmpty()) {
                                index = 0;
                                for (GenericValue adjustment : itemAdjustments ) {
                                    if(adjustment.get("productPromoId").equals(productPromoId)) {
                                        checkItem.getAdjustments().remove(index);
                                        result = "success";
                                    }
                                    index++;
                                }
                            }
                        }
                        cart.removeProductPromoUse(productPromoId);
                    }
                }
                cartUpdate.commit(cart); // SCIPIO
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
            }

            }
        }
        return result;
    }

    public static String addItemGroup(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> parameters = UtilHttp.getParameterMap(request);
        String groupName = (String) parameters.get("groupName");
        String parentGroupNumber = (String) parameters.get("parentGroupNumber");

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        String groupNumber = cart.addItemGroup(groupName, parentGroupNumber);
        request.setAttribute("itemGroupNumber", groupNumber);

        cartUpdate.commit(cart);
        }
        return "success";
    }

    public static String addCartItemToGroup(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> parameters = UtilHttp.getParameterMap(request);
        String itemGroupNumber = (String) parameters.get("itemGroupNumber");
        String indexStr = (String) parameters.get("lineIndex");
        int index = Integer.parseInt(indexStr);

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        
        ShoppingCartItem cartItem = cart.findCartItem(index);
        cartItem.setItemGroup(itemGroupNumber, cart);

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Event to add an item to the shopping cart. */
    public static String addToCart(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String controlDirective = null;
        Map<String, Object> result = null;
        String productId = null;
        String parentProductId = null;
        String itemType = null;
        String itemDescription = null;
        String productCategoryId = null;
        String priceStr = null;
        BigDecimal price = null;
        String quantityStr = null;
        BigDecimal quantity = BigDecimal.ZERO;
        String reservStartStr = null;
        String reservEndStr = null;
        Timestamp reservStart = null;
        Timestamp reservEnd = null;
        String reservLengthStr = null;
        BigDecimal reservLength = null;
        String reservPersonsStr = null;
        BigDecimal reservPersons = null;
        String accommodationMapId = null;
        String accommodationSpotId = null;
        String shipBeforeDateStr = null;
        String shipAfterDateStr = null;
        Timestamp shipBeforeDate = null;
        Timestamp shipAfterDate = null;
        String numberOfDay = null;

        // not used right now: Map attributes = null;
        String catalogId = CatalogWorker.getCurrentCatalogId(request);
        Locale locale = UtilHttp.getLocale(request);

        // Get the parameters as a MAP, remove the productId and quantity params.
        Map<String, Object> paramMap = UtilHttp.getCombinedMap(request);

        String itemGroupNumber = (String) paramMap.get("itemGroupNumber");

        // Get shoppingList info if passed
        String shoppingListId = (String) paramMap.get("shoppingListId");
        String shoppingListItemSeqId = (String) paramMap.get("shoppingListItemSeqId");
        if (paramMap.containsKey("ADD_PRODUCT_ID")) {
            productId = (String) paramMap.remove("ADD_PRODUCT_ID");
        } else if (paramMap.containsKey("add_product_id")) {
            Object object = paramMap.remove("add_product_id");
            try {
                productId = (String) object;
            } catch (ClassCastException e) {
                List<String> productList = UtilGenerics.checkList(object);
                productId = productList.get(0);
            }
        }
        if (paramMap.containsKey("PRODUCT_ID")) {
            parentProductId = (String) paramMap.remove("PRODUCT_ID");
        } else if (paramMap.containsKey("product_id")) {
            parentProductId = (String) paramMap.remove("product_id");
        }

        Debug.logInfo("adding item product " + productId, module);
        Debug.logInfo("adding item parent product " + parentProductId, module);

        if (paramMap.containsKey("ADD_CATEGORY_ID")) {
            productCategoryId = (String) paramMap.remove("ADD_CATEGORY_ID");
        } else if (paramMap.containsKey("add_category_id")) {
            productCategoryId = (String) paramMap.remove("add_category_id");
        }
        if (productCategoryId != null && productCategoryId.length() == 0) {
            productCategoryId = null;
        }

        if (paramMap.containsKey("ADD_ITEM_TYPE")) {
            itemType = (String) paramMap.remove("ADD_ITEM_TYPE");
        } else if (paramMap.containsKey("add_item_type")) {
            itemType = (String) paramMap.remove("add_item_type");
        }

        if (UtilValidate.isEmpty(productId)) {
            // before returning error; check make sure we aren't adding a special item type
            if (UtilValidate.isEmpty(itemType)) {
                request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.noProductInfoPassed", locale));
                return "success"; // not critical return to same page
            }
        } else {
            try {
                String pId = ProductWorker.findProductId(delegator, productId);
                if (pId != null) {
                    productId = pId;
                } else {
                    // SCIPIO: New error check: I currently don't see any case where we should continue if productId is set but not a valid product
                    // in the system. If itemType is specified (see above), you probably still shouldn't pass a productId
                    // that does not exist...
                    request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(product_resource_error, "productservices.invalid_productId_passed", locale));
                    return "success"; // not critical return to same page
                }
            } catch (Throwable e) {
                Debug.logWarning(e, module);
            }
        }

        // check for an itemDescription
        if (paramMap.containsKey("ADD_ITEM_DESCRIPTION")) {
            itemDescription = (String) paramMap.remove("ADD_ITEM_DESCRIPTION");
        } else if (paramMap.containsKey("add_item_description")) {
            itemDescription = (String) paramMap.remove("add_item_description");
        }
        if (itemDescription != null && itemDescription.length() == 0) {
            itemDescription = null;
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
            
        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        
        // Get the ProductConfigWrapper (it's not null only for configurable items)
        ProductConfigWrapper configWrapper = null;
        configWrapper = ProductConfigWorker.getProductConfigWrapper(productId, cart.getCurrency(), request);

        if (configWrapper != null) {
            if (paramMap.containsKey("configId")) {
                try {
                    configWrapper.loadConfig(delegator, (String) paramMap.remove("configId"));
                } catch (Exception e) {
                    Debug.logWarning(e, "Could not load configuration", module);
                }
            } else {
                // The choices selected by the user are taken from request and set in the wrapper
                ProductConfigWorker.fillProductConfigWrapper(configWrapper, request);
            }
            if (!configWrapper.isCompleted()) {
                // The configuration is not valid
                request.setAttribute("product_id", productId);
                request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.configureProductBeforeAddingToCart", locale));
                return "product";
            }
            // load the Config Id
            ProductConfigWorker.storeProductConfigWrapper(configWrapper, delegator);
        }


        //Check for virtual products
        if (ProductWorker.isVirtual(delegator, productId)) {

            if ("VV_FEATURETREE".equals(ProductWorker.getProductVirtualVariantMethod(delegator, productId))) {
                // get the selected features.
                List<String> selectedFeatures = new LinkedList<>();
                Enumeration<String> paramNames = UtilGenerics.cast(request.getParameterNames());
                while (paramNames.hasMoreElements()) {
                    String paramName = paramNames.nextElement();
                    if (paramName.startsWith("FT")) {
                        selectedFeatures.add(request.getParameterValues(paramName)[0]);
                    }
                }

                // check if features are selected
                if (UtilValidate.isEmpty(selectedFeatures)) {
                    request.setAttribute("paramMap", paramMap);
                    request.setAttribute("product_id", productId);
                    request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.chooseVariationBeforeAddingToCart", locale));
                    return "product";
                }

                String variantProductId = ProductWorker.getVariantFromFeatureTree(productId, selectedFeatures, delegator);
                if (UtilValidate.isNotEmpty(variantProductId)) {
                    productId = variantProductId;
                } else {
                    request.setAttribute("paramMap", paramMap);
                    request.setAttribute("product_id", productId);
                    request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.incompatibilityVariantFeature", locale));
                    return "product";
                }

            } else {
                request.setAttribute("paramMap", paramMap);
                request.setAttribute("product_id", productId);
                request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.chooseVariationBeforeAddingToCart", locale));
                return "product";
            }
        }

        // get the override price
        if (paramMap.containsKey("PRICE")) {
            priceStr = (String) paramMap.remove("PRICE");
        } else if (paramMap.containsKey("price")) {
            priceStr = (String) paramMap.remove("price");
        }
        if (priceStr == null) {
            priceStr = "0";  // default price is 0
        }

        if ("ASSET_USAGE_OUT_IN".equals(ProductWorker.getProductTypeId(delegator, productId))) {
            if (paramMap.containsKey("numberOfDay")) {
                numberOfDay = (String) paramMap.remove("numberOfDay");
                reservStart = UtilDateTime.addDaysToTimestamp(UtilDateTime.nowTimestamp(), 1);
                reservEnd = UtilDateTime.addDaysToTimestamp(reservStart, Integer.parseInt(numberOfDay));
            }
        }

        // get the renting data
        if ("ASSET_USAGE".equals(ProductWorker.getProductTypeId(delegator, productId)) || "ASSET_USAGE_OUT_IN".equals(ProductWorker.getProductTypeId(delegator, productId))) {
            if (paramMap.containsKey("reservStart")) {
                reservStartStr = (String) paramMap.remove("reservStart");
                if (reservStartStr.length() == 10) { // only date provided, no time string?
                    reservStartStr += " 00:00:00.000000000"; // should have format: yyyy-mm-dd hh:mm:ss.fffffffff
                }
                if (reservStartStr.length() > 0) {
                    try {
                        reservStart = java.sql.Timestamp.valueOf(reservStartStr);
                    } catch (Exception e) {
                        Debug.logWarning(e, "Problems parsing Reservation start string: "
                                + reservStartStr, module);
                        reservStart = null;
                        request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.rental.startDate", locale));
                        return "error";
                    }
                } else {
                    reservStart = null;
                }
            }

            if (paramMap.containsKey("reservEnd")) {
                reservEndStr = (String) paramMap.remove("reservEnd");
                if (reservEndStr.length() == 10) { // only date provided, no time string?
                    reservEndStr += " 00:00:00.000000000"; // should have format: yyyy-mm-dd hh:mm:ss.fffffffff
                }
                if (reservEndStr.length() > 0) {
                    try {
                        reservEnd = java.sql.Timestamp.valueOf(reservEndStr);
                    } catch (Exception e) {
                        Debug.logWarning(e, "Problems parsing Reservation end string: " + reservEndStr, module);
                        reservEnd = null;
                        request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.rental.endDate", locale));
                        return "error";
                    }
                } else {
                    reservEnd = null;
                }
            }

            if (reservStart != null && reservEnd != null) {
                reservLength = new BigDecimal(UtilDateTime.getInterval(reservStart, reservEnd)).divide(new BigDecimal("86400000"), generalRounding);
            }

            if (reservStart != null && paramMap.containsKey("reservLength")) {
                reservLengthStr = (String) paramMap.remove("reservLength");
                // parse the reservation Length
                try {
                    reservLength = (BigDecimal) ObjectType.simpleTypeConvert(reservLengthStr, "BigDecimal", null, locale);
                } catch (Exception e) {
                    Debug.logWarning(e, "Problems parsing reservation length string: "
                            + reservLengthStr, module);
                    reservLength = BigDecimal.ONE;
                    request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderReservationLengthShouldBeAPositiveNumber", locale));
                    return "error";
                }
            }

            if (reservStart != null && paramMap.containsKey("reservPersons")) {
                reservPersonsStr = (String) paramMap.remove("reservPersons");
                // parse the number of persons
                try {
                    reservPersons = (BigDecimal) ObjectType.simpleTypeConvert(reservPersonsStr, "BigDecimal", null, locale);
                } catch (Exception e) {
                    Debug.logWarning(e, "Problems parsing reservation number of persons string: " + reservPersonsStr, module);
                    reservPersons = BigDecimal.ONE;
                    request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderNumberOfPersonsShouldBeOneOrLarger", locale));
                    return "error";
                }
            }

            //check for valid rental parameters
            if (UtilValidate.isEmpty(reservStart) && UtilValidate.isEmpty(reservLength) && UtilValidate.isEmpty(reservPersons)) {
                request.setAttribute("product_id", productId);
                request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.enterBookingInforamtionBeforeAddingToCart", locale));
                return "product";
            }

            //check accommodation for reservations
            if ((paramMap.containsKey("accommodationMapId")) && (paramMap.containsKey("accommodationSpotId"))) {
                accommodationMapId = (String) paramMap.remove("accommodationMapId");
                accommodationSpotId = (String) paramMap.remove("accommodationSpotId");
            }
        }

        // get the quantity
        if (paramMap.containsKey("QUANTITY")) {
            quantityStr = (String) paramMap.remove("QUANTITY");
        } else if (paramMap.containsKey("quantity")) {
            quantityStr = (String) paramMap.remove("quantity");
        }
        if (UtilValidate.isEmpty(quantityStr)) {
            quantityStr = "1";  // default quantity is 1
        }

        // parse the price
        try {
            price = (BigDecimal) ObjectType.simpleTypeConvert(priceStr, "BigDecimal", null, locale);
        } catch (Exception e) {
            Debug.logWarning(e, "Problems parsing price string: " + priceStr, module);
            price = null;
        }

        // parse the quantity
        try {
            quantity = (BigDecimal) ObjectType.simpleTypeConvert(quantityStr, "BigDecimal", null, locale);
            //For quantity we should test if we allow to add decimal quantity for this product an productStore :
            // if not and if quantity is in decimal format then return error.
            if(! ProductWorker.isDecimalQuantityOrderAllowed(delegator, productId, cart.getProductStoreId())){
                BigDecimal remainder = quantity.remainder(BigDecimal.ONE);
                if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                    request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.quantityInDecimalNotAllowed", locale));
                    return "error";
                }
                quantity = quantity.setScale(0, UtilNumber.getRoundingMode("order.rounding"));
            }
            else {
                quantity = quantity.setScale(UtilNumber.getBigDecimalScale("order.decimals"), UtilNumber.getRoundingMode("order.rounding"));
            }
        } catch (Exception e) {
            Debug.logWarning(e, "Problems parsing quantity string: " + quantityStr, module);
            quantity = BigDecimal.ONE;
        }

        // get the selected amount
        String selectedAmountStr = null;
        if (paramMap.containsKey("ADD_AMOUNT")) {
            selectedAmountStr = (String) paramMap.remove("ADD_AMOUNT");
        } else if (paramMap.containsKey("add_amount")) {
            selectedAmountStr = (String) paramMap.remove("add_amount");
        }

        // parse the amount
        BigDecimal amount = null;
        if (UtilValidate.isNotEmpty(selectedAmountStr)) {
            try {
                amount = (BigDecimal) ObjectType.simpleTypeConvert(selectedAmountStr, "BigDecimal", null, locale);
            } catch (Exception e) {
                Debug.logWarning(e, "Problem parsing amount string: " + selectedAmountStr, module);
                amount = null;
            }
        } else {
            amount = BigDecimal.ZERO;
        }

        // check for required amount
        if ((ProductWorker.isAmountRequired(delegator, productId)) && (amount == null || amount.doubleValue() == 0.0)) {
            request.setAttribute("product_id", productId);
            request.setAttribute("_EVENT_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.enterAmountBeforeAddingToCart", locale));
            return "product";
        }

        // get the ship before date (handles both yyyy-mm-dd input and full timestamp)
        shipBeforeDateStr = (String) paramMap.remove("shipBeforeDate");
        if (UtilValidate.isNotEmpty(shipBeforeDateStr)) {
            if (shipBeforeDateStr.length() == 10) {
                shipBeforeDateStr += " 00:00:00.000";
            }
            try {
                shipBeforeDate = java.sql.Timestamp.valueOf(shipBeforeDateStr);
            } catch (IllegalArgumentException e) {
                Debug.logWarning(e, "Bad shipBeforeDate input: " + e.getMessage(), module);
                shipBeforeDate = null;
            }
        }

        // get the ship after date (handles both yyyy-mm-dd input and full timestamp)
        shipAfterDateStr = (String) paramMap.remove("shipAfterDate");
        if (UtilValidate.isNotEmpty(shipAfterDateStr)) {
            if (shipAfterDateStr.length() == 10) {
                shipAfterDateStr += " 00:00:00.000";
            }
            try {
                shipAfterDate = java.sql.Timestamp.valueOf(shipAfterDateStr);
            } catch (IllegalArgumentException e) {
                Debug.logWarning(e, "Bad shipAfterDate input: " + e.getMessage(), module);
                shipAfterDate = null;
            }
        }

        // check for an add-to cart survey
        List<String> surveyResponses = null;
        if (productId != null) {
            String productStoreId = ProductStoreWorker.getProductStoreId(request);
            List<GenericValue> productSurvey = ProductStoreWorker.getProductSurveys(delegator, productStoreId, productId, "CART_ADD", parentProductId);
            if (UtilValidate.isNotEmpty(productSurvey)) {
                // TODO: implement multiple survey per product
                GenericValue survey = EntityUtil.getFirst(productSurvey);
                String surveyResponseId = (String) request.getAttribute("surveyResponseId");
                if (surveyResponseId != null) {
                    surveyResponses = UtilMisc.toList(surveyResponseId);
                } else {
                    String origParamMapId = UtilHttp.stashParameterMap(request);
                    Map<String, Object> surveyContext = UtilMisc.<String, Object>toMap("_ORIG_PARAM_MAP_ID_", origParamMapId,
                            "productStoreSurveyId", survey.get("productStoreSurveyId")); // SCIPIO: 2019-03-06: Added productStoreSurveyId
                    // SCIPIO: Logic error: in orderentry it's the placing party whose login should be set here
                    //GenericValue userLogin = cart.getUserLogin();
                    //String partyId = null;
                    //if (userLogin != null) {
                    //    partyId = userLogin.getString("partyId");
                    //}
                    String partyId = null;
                    if ("SALES_ORDER".equals(cart.getOrderType())) {
                        partyId = cart.getPlacingCustomerPartyId();
                    }
                    if (UtilValidate.isEmpty(partyId)) {
                        if ("PURCHASE_ORDER".equals(cart.getOrderType())) {
                            partyId = cart.getSupplierAgentPartyId();       
                        }
                        if (UtilValidate.isEmpty(partyId)) { // fallback (stock case)
                            GenericValue userLogin = cart.getUserLogin();
                            if (userLogin != null) {
                                partyId = userLogin.getString("partyId");
                            }
                        }
                    }
                    String formAction = "/additemsurvey";
                    String nextPage = RequestHandler.getOverrideViewUri(request.getPathInfo());
                    if (nextPage != null) {
                        formAction = formAction + "/" + nextPage;
                    }
                    ProductStoreSurveyWrapper wrapper = new ProductStoreSurveyWrapper(survey, partyId, surveyContext);
                    request.setAttribute("surveyWrapper", wrapper);
                    request.setAttribute("surveyAction", formAction); // will be used as the form action of the survey
                    request.setAttribute("productStoreSurveyId", survey.get("productStoreSurveyId")); // SCIPIO: 2019-03-06: for backward-compatibility (missing in stock)
                    request.setAttribute("_ORIG_PARAM_MAP_ID_", origParamMapId); // SCIPIO: 2019-03-06: for backward-compatibility (missing in stock)
                    return "survey";
                }
            }
        }
        if (surveyResponses != null) {
            paramMap.put("surveyResponses", surveyResponses);
        }

        GenericValue productStore = ProductStoreWorker.getProductStore(request);
        if (productStore != null) {
            String addToCartRemoveIncompat = productStore.getString("addToCartRemoveIncompat");
            String addToCartReplaceUpsell = productStore.getString("addToCartReplaceUpsell");
            try {
                if ("Y".equals(addToCartRemoveIncompat)) {
                    List<GenericValue> productAssocs = null;
                    EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toList(
                            EntityCondition.makeCondition(EntityCondition.makeCondition("productId", EntityOperator.EQUALS, productId), EntityOperator.OR, EntityCondition.makeCondition("productIdTo", EntityOperator.EQUALS, productId)),
                            EntityCondition.makeCondition("productAssocTypeId", EntityOperator.EQUALS, "PRODUCT_INCOMPATABLE")), EntityOperator.AND);
                    productAssocs = EntityQuery.use(delegator).from("ProductAssoc").where(cond).filterByDate().queryList();
                    List<String> productList = new LinkedList<>();
                    for (GenericValue productAssoc : productAssocs) {
                        if (productId.equals(productAssoc.getString("productId"))) {
                            productList.add(productAssoc.getString("productIdTo"));
                            continue;
                        }
                        if (productId.equals(productAssoc.getString("productIdTo"))) {
                            productList.add(productAssoc.getString("productId"));
                            continue;
                        }
                    }
                    for (ShoppingCartItem sci : cart) {
                        if (productList.contains(sci.getProductId())) {
                            try {
                                cart.removeCartItem(sci, dispatcher);
                            } catch (CartItemModifyException e) {
                                Debug.logError(e.getMessage(), module);
                            }
                        }
                    }
                }
                if ("Y".equals(addToCartReplaceUpsell)) {
                    List<GenericValue> productList = null;
                    productList = EntityQuery.use(delegator).select("productId").from("ProductAssoc").where("productIdTo", productId, "productAssocTypeId", "PRODUCT_UPGRADE").queryList();
                    if (productList != null) {
                        for (ShoppingCartItem sci : cart) {
                            if (productList.parallelStream().anyMatch(p -> sci.getProductId().equals(p.getString("productId")))) {
                                try {
                                    cart.removeCartItem(sci, dispatcher);
                                } catch (CartItemModifyException e) {
                                    Debug.logError(e.getMessage(), module);
                                }
                            }
                        }
                    }
                }
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
            }
        }

        // check for alternative packing
        if(ProductWorker.isAlternativePacking(delegator, productId , parentProductId)){
            GenericValue parentProduct = null;
            try {
                parentProduct = EntityQuery.use(delegator).from("Product").where("productId", parentProductId).queryOne();
            } catch (GenericEntityException e) {
                Debug.logError(e, "Error getting parent product", module);
            }
            BigDecimal piecesIncluded = BigDecimal.ZERO;
            if(parentProduct != null){
                piecesIncluded = new BigDecimal(parentProduct.getLong("piecesIncluded"));
                quantity = quantity.multiply(piecesIncluded);
            }
        }

        // Translate the parameters and add to the cart
        result = cartHelper.addToCart(catalogId, shoppingListId, shoppingListItemSeqId, productId, productCategoryId,
                itemType, itemDescription, price, amount, quantity, reservStart, reservLength, reservPersons,
                accommodationMapId, accommodationSpotId,
                shipBeforeDate, shipAfterDate, configWrapper, itemGroupNumber, paramMap, parentProductId);
        controlDirective = processResult(result, request);

        Integer itemId = (Integer)result.get("itemId");
        if (UtilValidate.isNotEmpty(itemId)) {
            request.setAttribute("itemId", itemId);
        }
        try {
            GenericValue product = EntityQuery.use(delegator).from("Product").where("productId", productId).queryOne();
            //Reset shipment method information in cart only if shipping applies on product.
            if (UtilValidate.isNotEmpty(product) && ProductWorker.shippingApplies(product)) {
                for (int shipGroupIndex = 0; shipGroupIndex < cart.getShipGroupSize(); shipGroupIndex++) {
                    String shipContactMechId = cart.getShippingContactMechId(shipGroupIndex);
                    if (UtilValidate.isNotEmpty(shipContactMechId)) {
                        cart.setShipmentMethodTypeId(shipGroupIndex, null);
                    }
                }
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, "Error getting product"+e.getMessage(), module);
        }
        // Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }
        
        cartUpdate.commit(cart); // SCIPIO
        
        if (cart.viewCartOnAdd()) {
            return "viewcart";
        }

        }

        return "success";
    }

    public static String addToCartFromOrder(HttpServletRequest request, HttpServletResponse response) {
        String orderId = request.getParameter("orderId");
        String itemGroupNumber = request.getParameter("itemGroupNumber");
        String[] itemIds = request.getParameterValues("item_id");
        
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String catalogId = CatalogWorker.getCurrentCatalogId(request);
        Map<String, Object> result;
        String controlDirective;

        boolean addAll = ("true".equals(request.getParameter("add_all")));
        
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        
        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        result = cartHelper.addToCartFromOrder(catalogId, orderId, itemIds, addAll, itemGroupNumber);
        controlDirective = processResult(result, request);

        //Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }
        
        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Adds all products in a category according to quantity request parameter
     * for each; if no parameter for a certain product in the category, or if
     * quantity is 0, do not add
     */
    public static String addToCartBulk(HttpServletRequest request, HttpServletResponse response) {
        String categoryId = request.getParameter("category_id");
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String controlDirective;
        Map<String, Object> result;
        //Convert the params to a map to pass in
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);
        String catalogId = CatalogWorker.getCurrentCatalogId(request);

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        
        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        result = cartHelper.addToCartBulk(catalogId, categoryId, paramMap);
        controlDirective = processResult(result, request);

        //Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }
        
        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    public static String quickInitPurchaseOrder(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        //HttpSession session = request.getSession();
        Locale locale = UtilHttp.getLocale(request);
        String supplierPartyId = request.getParameter("supplierPartyId_o_0");

        // SCIPIO: NOTE: This must synchronize early because WebShoppingCart modifies session attributes
        try (CartSync cartSync = CartSync.synchronizedSection(request)) { // SCIPIO

        // check the preferred currency of the supplier, if set, use that for the cart, otherwise use system defaults.
        ShoppingCart cart = null;
        try {
            GenericValue supplierParty = EntityQuery.use(delegator).from("Party").where("partyId", supplierPartyId).queryOne();
            if (UtilValidate.isNotEmpty(supplierParty.getString("preferredCurrencyUomId"))) {
                cart = ShoppingCartFactory.createWebShoppingCart(request, locale, supplierParty.getString("preferredCurrencyUomId")); // SCIPIO: use factory
            } else {
                cart = ShoppingCartFactory.createWebShoppingCart(request); // SCIPIO: use factory
            }
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
        }

        // TODO: the code below here needs some cleanups
        String billToCustomerPartyId = request.getParameter("billToCustomerPartyId_o_0");
        if (UtilValidate.isEmpty(billToCustomerPartyId) && UtilValidate.isEmpty(supplierPartyId)) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderCouldNotInitPurchaseOrder", locale));
            return "error";
        }
        String orderId = request.getParameter("orderId_o_0");
        // set the order id if supplied
        if (UtilValidate.isNotEmpty(orderId)) {
            GenericValue thisOrder = null;
            try {
                thisOrder = EntityQuery.use(delegator).from("OrderHeader").where("orderId", orderId).queryOne();
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
            }
            if (thisOrder == null) {
                cart.setOrderId(orderId);
            } else {
                request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderIdAlreadyExistsPleaseChooseAnother", locale));
                return "error";
            }
        }
        cart.setBillToCustomerPartyId(billToCustomerPartyId);
        cart.setBillFromVendorPartyId(supplierPartyId);
        cart.setOrderPartyId(supplierPartyId);
        cart.setOrderId(orderId);
        String agreementId = request.getParameter("agreementId_o_0");
        if (UtilValidate.isNotEmpty(agreementId)) {
            ShoppingCartHelper sch = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
            sch.selectAgreement(agreementId);
        }

        cart.setOrderType("PURCHASE_ORDER");

        setCartObjectAndAttr(request, cart); // SCIPIO: refactored
        }

        return "success";
    }

    public static String quickCheckoutOrderWithDefaultOptions(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        // Set the cart's default checkout options for a quick checkout
        cart.setDefaultCheckoutOptions(dispatcher);

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Adds a set of requirements to the cart
     */
    public static String addToCartBulkRequirements(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String controlDirective;
        Map<String, Object> result;
        //Convert the params to a map to pass in
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);
        String catalogId = CatalogWorker.getCurrentCatalogId(request);

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        
        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        result = cartHelper.addToCartBulkRequirements(catalogId, paramMap);
        controlDirective = processResult(result, request);

        //Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Adds all products in a category according to default quantity on ProductCategoryMember
     * for each; if no default for a certain product in the category, or if
     * quantity is 0, do not add
     */
    public static String addCategoryDefaults(HttpServletRequest request, HttpServletResponse response) {
        String itemGroupNumber = request.getParameter("itemGroupNumber");
        String categoryId = request.getParameter("category_id");
        String catalogId = CatalogWorker.getCurrentCatalogId(request);
        
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        
        String controlDirective;
        Map<String, Object> result;
        BigDecimal totalQuantity;
        Locale locale = UtilHttp.getLocale(request);

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        result = cartHelper.addCategoryDefaults(catalogId, categoryId, itemGroupNumber);
        controlDirective = processResult(result, request);

        //Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }

        totalQuantity = (BigDecimal) result.get("totalQuantity");
        Map<String, Object> messageMap = UtilMisc.<String, Object>toMap("totalQuantity", UtilFormatOut.formatQuantity(
                totalQuantity.doubleValue()));

        request.setAttribute("_EVENT_MESSAGE_",
                UtilProperties.getMessage(resource_error, "cart.add_category_defaults",
                        messageMap, locale));

        return "success";
    }

    /** Delete an item from the shopping cart. */
    public static String deleteFromCart(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String controlDirective;
        Map<String, Object> result;
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);

        //Delegate to the cart helper
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        
        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(null, dispatcher, cart);
        result = cartHelper.deleteFromCart(paramMap);
        controlDirective = processResult(result, request);

        //Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Update the items in the shopping cart. */
    public static String modifyCart(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        Locale locale = UtilHttp.getLocale(request);
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        Security security = (Security) request.getAttribute("security");
        String controlDirective;
        Map<String, Object> result;
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);

        String removeSelectedFlag = request.getParameter("removeSelected");
        String selectedItems[] = request.getParameterValues("selectedItem");
        boolean removeSelected = ("true".equals(removeSelectedFlag) && selectedItems != null && selectedItems.length > 0);
        
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        request.setAttribute("shoppingCartSize", cart.size()); // SCIPIO

        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(null, dispatcher, cart);
        result = cartHelper.modifyCart(security, userLogin, paramMap, removeSelected, selectedItems, locale);
        controlDirective = processResult(result, request);

        //Determine where to send the browser
        if (controlDirective.equals(ERROR)) {
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        request.setAttribute("shoppingCartSize", cart.size()); // SCIPIO
        }
        return "success";
    }

    public static String modifyCartAndGetCartData(HttpServletRequest request, HttpServletResponse response) { // SCIPIO
        return getShoppingCartDataAfterEvent(request, response, modifyCart(request, response));
    }

    /**
     * Gets shopping cart data after an event without losing the original event response or error messages (SCIPIO).
     */
    public static String getShoppingCartDataAfterEvent(HttpServletRequest request, HttpServletResponse response, String eventResponse) { // SCIPIO
        Map<String, Object> modifyCartMsgs = EventUtil.getEventErrorAttributesAsMap(request);
        Map<String, Object> servCtx = EventUtil.getServiceEventParamMap(request, "getShoppingCartData"); // FIXME: EventUtil will be replaced later
        ShoppingListWorker.checkSetShoppingListAuthTokenForService(request, servCtx);
        String cartDataResult = null;
        try {
            cartDataResult = EventUtil.runServiceAsEvent(request, response, "getShoppingCartData", servCtx);
        } catch (GenericServiceException e) {
            Debug.logError(e, "getShoppingCartDataAfterEvent exception: " + e.toString(), module);
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage("CommonErrorUiLabels", "CommonErrorOccurredContactSupport", UtilHttp.getLocale(request)));
            request.removeAttribute("_ERROR_MESSAGE_LIST_");
            cartDataResult = "error";
        }
        if ("error".equals(eventResponse) || !"error".equals(cartDataResult)) { // NOTE: modifyCart result messages takes precedence
            EventUtil.setEventErrorAttributesFromMap(request, modifyCartMsgs);
        }
        return "error".equals(cartDataResult) ? cartDataResult : eventResponse;
    }

    /** Empty the shopping cart. */
    public static String clearCart(HttpServletRequest request, HttpServletResponse response) {
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        cart.clear();

        cartUpdate.commit(cart); // SCIPIO
        }

        // if this was an anonymous checkout process, go ahead and clear the session and such now that the order is placed; we don't want this to mess up additional orders and such
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        if (userLogin != null && "anonymous".equals(userLogin.get("userLoginId"))) {
            Locale locale = UtilHttp.getLocale(session);

            // here we want to do a full logout, but not using the normal logout stuff because it saves things in the UserLogin record that we don't want changed for the anonymous user
            session.invalidate();
            removeCartObject(request, RequestVarScopes.REQUEST); // SCIPIO: 2018-12-03: Ensure cart request attribute is removed

            session = request.getSession(true);
            if (null != locale) {
                UtilHttp.setLocale(session, locale);
            }

            // to allow the display of the order confirmation page put the userLogin in the request, but leave it out of the session
            request.setAttribute("temporaryAnonymousUserLogin", userLogin);

            // SCIPIO: Changing this behavior to PRESERVE the previous anon user login (especially the temporary userLogin.partyId).
            // This is to allow the anon user to view additional order documents after the next immediate request.
            // If we don't do this, the anon user only gets to see one page and any refresh
            // causes him to lose access to all information not mailed to him.
            // Keeping the anon userLogin also allows keeping the previous temporary partyId which
            // will conveniently remember his address if he places another order.
            // I do not see a reason why keeping userLogin should cause any problems for screens,
            // because all screens must be "anonymous"-login aware anyway (because of multiple tabs).
            // NOTE: The comment above says that UserLogin is modified for logouts, but our LoginWorker should be
            // patched to prevent this for the anonymous user.
            //Debug.logInfo("Doing clearCart for anonymous user, so logging out but put anonymous userLogin in temporaryAnonymousUserLogin request attribute", module);
            session.setAttribute("userLogin", userLogin);
            Debug.logInfo("Scipio: Doing clearCart for anonymous user, but leaving anonymous user login in session (partyId: '" + userLogin.getString("partyId") + "'", module);
        }

        return "success";
    }

    /**
     * SCIPIO: Clears the cart, and if it gets destroyed in the process, make sure another is created.
     * <p>
     * Helps with template compatibility.
     */
    public static String clearEnsureCart(HttpServletRequest request, HttpServletResponse response) {
        String result;

        result = clearCart(request, response);
        if (!"success".equals(result)) {
            return "error";
        }
        ShoppingCart cart = getCartObject(request);
        if (cart == null) { // NOTE: this should never happen
            return "error";
        }

        return "success";
    }

    /**
     * SCIPIO: Checks if cart empty and valid. If missing or empty, returns cartEmpty. If broken some other way,
     * returns "error". Otherwise, returns "success".
     * <p>
     * Helps with template compatibility.
     * <p>
     * NOTE: There is a org.ofbiz.order.shoppingcart.CheckOutEvents.cartNotEmpty, but it returns
     * error when cart empty.
     */
    public static String checkCart(HttpServletRequest request, HttpServletResponse response) {
        ShoppingCart cart = getCartObject(request);
        if (cart == null || cart.size() <= 0) {
            return "cartEmpty";
        }

        return "success";
    }

    /** Totally wipe out the cart, removes all stored info. */
    public static String destroyCart(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        try (CartSync cartSync = CartSync.synchronizedSection(request)) { // SCIPIO
        clearCart(request, response);
        // SCIPIO: 2018-11-29: Clear the request attribute as well, otherwise most cases are nonsensical (a few ambiguous)
        //session.removeAttribute("shoppingCart");
        removeCartObject(request);
        session.removeAttribute("orderPartyId");
        session.removeAttribute("orderMode");
        session.removeAttribute("productStoreId");
        session.removeAttribute("CURRENT_CATALOG_ID");
        }
        return "success";
    }

    /** Gets or creates the shopping cart object.
     * <p>
     * SCIPIO: This method is rewritten.
     * <p>
     * SCIPIO: WARNING: 2018-12-03: The explicit <code>locale</code> and <code>currencyUom</code> parameters are completely
     * removed because they complicated the cart get code with cart updates too much.
     * <p>
     * SCIPIO: NOTE: 2018-12-03: This no longer transfers the "shoppingCart" request attribute to the session
     * attributes, and instead does the opposite: it transfers session attribute to request attribute in order to ensure
     * cart instance read atomicity for the request. NOTE: However, CartUpdate sessions (upon entry) actually read
     * the session first.
     * <p>
     * NOTE: If locale or currency has changed, <code>checkRequestFirst</code> may be ignored, because cart changes
     * should always 
     */
    static ShoppingCart getCartObject(HttpServletRequest request, boolean createifMissing, boolean checkRequestFirst, RequestVarScopes modifyScopesFilter) { // SCIPIO: added checkRequestFirst, modifyScopesFilter
        // SCIPIO: Heavily refactored for synchronized cart updates
        ShoppingCart cart = null, requestCart = (ShoppingCart) request.getAttribute("shoppingCart");
        if (checkRequestFirst) {
            // SCIPIO: Check request attribute first, typically for pure cart reads during a request, which use
            // local request cache for consistency in the rendering
            cart = requestCart;
            if (cart == null) {
                HttpSession session = request.getSession(false);
                if (session != null) {
                    cart = (ShoppingCart) session.getAttribute("shoppingCart");
                }
            } else {
                // SCIPIO: 2018-11-30: This code was destructive; do not automatically transfer the request attribute to 
                // session anymore; instead, setCartObject and CartUpdate#close() do it, and as bonus we avoid extra sync blocks.
                // NOTE: We now do the opposite further below and transfer session to request instead.
                //request.getSession().setAttribute("shoppingCart", cart);
            }
        } else {
            // SCIPIO: Check session attributes first, typically only done at the beginning of a CartUpdate section
            // (required there to avoid lost cart updates from other threads)
            HttpSession session = request.getSession(false);
            if (session != null) {
                cart = (ShoppingCart) session.getAttribute("shoppingCart");
            }
            if (cart == null) {
                cart = requestCart;
            }
        }

        if (createifMissing && cart == null) {
            try (CartSync cartSync = CartSync.synchronizedSection(request)) {
                // SCIPIO: Check session cart again inside synchronized (only session might have changed)
                HttpSession session = request.getSession(false);
                ShoppingCart sessionCart = (session != null) ? (ShoppingCart) session.getAttribute("shoppingCart") : null;
                cart = sessionCart;
                if (cart == null) {
                    // NEW CART
                    cart = ShoppingCartFactory.createWebShoppingCart(request); // SCIPIO: use factory
                    // Update both session and req attr (or per modifyScopesFilter)
                    ShoppingCartEvents.setCartObject(request, cart, modifyScopesFilter);
                    requestCart = cart; // Don't re-update below; done by setCartObject
                }
            }
        }

        // SCIPIO: 2018-11-30: Do the exact opposite of request-to-session copy above, and if session is found, transfer session-to-request.
        // This helps ensure that read-only cart accesses in the rest of this request will all use the same cart.
        // NOTE: However, cart update blocks do the opposite and read cart to modify from session first - see CartUpdate.begin().
        // NOTE: Requires that the req attr was already null; will not crush it (caller - CartUpdate - will handle that as necessary)
        if (requestCart == null && modifyScopesFilter.request() && cart != null) {
            request.setAttribute("shoppingCart", cart);
        }
        return cart;
    }

    // SCIPIO: Removed update code related to old locale/currencyUom parameters, now removed
//    private static boolean checkCartCurrencyLocaleChanged(ShoppingCart cart, Locale locale, String currencyUom) { // SCIPIO
//        return ((locale != null && !locale.equals(cart.getLocale()))
//                || (currencyUom != null && !currencyUom.equals(cart.getCurrency())));
//    }
//    
//    /**
//     * SCIPIO: If the locale or currencyUom has changed, creates a cart copy with the new values and updates
//     * the request/session vars according to modifyScopesFilter.
//     */
//    private static ShoppingCart checkUpdateCartCurrencyLocale(HttpServletRequest request, ShoppingCart cart,
//            Locale locale, String currencyUom, CartUpdate cartUpdate, RequestVarScopes modifyScopesFilter) { // SCIPIO
//        boolean localeChanged = (locale != null && !locale.equals(cart.getLocale()));
//        boolean currencyChanged = (currencyUom != null && !currencyUom.equals(cart.getCurrency()));
//        
//        if (localeChanged || currencyChanged) {
//            cart = cartUpdate.copyCartForUpdate(cart); // make cart copy to edit
//
//            // CART CHANGED
//            if (localeChanged) {
//                cart.setLocale(locale);
//            }
//            if (currencyUom != null && !currencyUom.equals(cart.getCurrency())) {
//                LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
//                try {
//                    cart.setCurrency(dispatcher, currencyUom);
//                } catch (CartItemModifyException e) {
//                    Debug.logError(e, "Unable to modify currency in cart", module);
//                }
//            }
//            setCartObject(request, cart, modifyScopesFilter);
//        }
//        return cart;
//    }

    /** Gets or creates the shopping cart object.
     * @deprecated SCIPIO: 2018-12-03: DO NOT USE THIS OVERLOAD; locale and currencyUom are now ignored, because
     * the implicit updates would cause unexpected behavior and complications - updates to locale and currencyUom
     * should be done at clearly defined events, wrapped in {@link CartUpdate} sections.
     */
    @Deprecated
    public static ShoppingCart getCartObject(HttpServletRequest request, Locale locale, String currencyUom) {
        Debug.logWarning("DEPRECATED: getCartObject with explicit locale and currencyUom called (" + Debug.getCallerShortInfo() 
            + ") - not supported - locale and currency will not be updated in cart - please report this issue or fix calling code", module);
        return getCartObject(request, true, true, RequestVarScopes.ALL); // SCIPIO: checkRequestFirst, modifyScopesFilter
    }

    /** 
     * Main get cart method, uses the locale and currency from the session.
     * <p>
     * SCIPIO: NOTES:
     * <ul>
     * <li>If the cart does not yet exist, it is created and set in both request and session
     * attributes. If you do not want this, use {@link #getCartObjectIfExists(HttpServletRequest)} instead.
     * <li>2018-12-03: By default (checkRequestFirst==true), this method checks for existing cart in request attributes
     * first (in order for cart reads across the request to consistently use the same object); this contrasts
     * with {@link CartUpdate} sections where the cart is read from session attributes first (to make sure
     * updates from other threads are read).
     * <li>2018-12-03: This method automatically transfers the session cart to request attributes if not already set; it NO LONGER
     * transfers from request attributes to session attributes.
     * </ul>
     */
    public static ShoppingCart getCartObject(HttpServletRequest request) {
        return getCartObject(request, true, true, RequestVarScopes.ALL); // SCIPIO: checkRequestFirst, modifyScopesFilter
    }

    /** SCIPIO: Get cart method only if set, uses the locale and currency from the session. Added 2018-11-29.
     * NOTE: 2019-01: This now automatically transfers 
     * @see {@link #getCartObject(HttpServletRequest)} */
    public static ShoppingCart getCartObjectIfExists(HttpServletRequest request) {
        //return (ShoppingCart) RequestVarScopes.REQUEST_AND_SESSION.getValue(request, "shoppingCart");
        return getCartObject(request, false, true, RequestVarScopes.ALL);
    }

    /**
     * SCIPIO: Sets the cart in session and/or request as applicable immediately; if cart is null, removes the cart.
     * <p>
     * SPECIAL CASE: If session modification is enabled and we're in a CartUpdate session, this will instead
     * trigger a {@link CartUpdate#commit(ShoppingCart)} call, to prevent session modifications. Note that
     * this case may then also trigger a request attribute update regardless of modifyScopesFilter session.
     * <p>
     * NOTE: For synchronized updates, use {@link CartUpdate} instead of this directly; if you must use this,
     * it must always be wrapped in a {@link CartSync#synchronizedSection(HttpServletRequest)}.
     * NOTE: This automatically marks the cart as changed in the request, so do not reassign an unmodified cart.
     * Added 2018-11-20. */
    public static ShoppingCart setCartObject(HttpServletRequest request, ShoppingCart cart, RequestVarScopes modifyScopesFilter) {
        if (modifyScopesFilter.session()) {
            CartUpdate currentUpdate = CartUpdate.getCurrentUpdate(request);
            if (currentUpdate != null) {
                currentUpdate.commit(cart);
                return cart;
            }
        }
        if (ShoppingCart.verboseOn() && modifyScopesFilter.session()) {
            ShoppingCartChangeVerifier.getInstance().recordCart(cart);
        }
        RequestVarScopes.REQUEST_AND_SESSION.setOrRemoveValue(request, modifyScopesFilter, "shoppingCart", cart);
        if (modifyScopesFilter.session()) {
            markCartChanged(request);
        }
        return cart;
    }

    /**
     * SCIPIO: Sets the cart in session and request immediately; if cart is null, removes the cart.
     * <p>
     * NOTE: For synchronized updates, use {@link CartUpdate} instead of this directly; if you must use this,
     * it must always be wrapped in a {@link CartSync#synchronizedSection(HttpServletRequest)}.
     * NOTE: This automatically marks the cart as changed in the request, so do not reassign an unmodified cart.
     * Added 2018-11-20. */
    public static ShoppingCart setCartObject(HttpServletRequest request, ShoppingCart cart) {
        return setCartObject(request, cart, RequestVarScopes.ALL);
    }

    /**
     * SCIPIO: Sets the cart in session and request immediately as well as related cart attributes,
     * WITHOUT a cart synchronized section.
     * Added 2019-01. */
    public static ShoppingCart setCartObjectAndAttr(HttpServletRequest request, ShoppingCart cart) {
        cart = ShoppingCartEvents.setCartObject(request, cart); // SCIPIO: Use setter: session.setAttribute("shoppingCart", cart);
        HttpSession session = request.getSession();
        if (cart != null) {
            session.setAttribute("productStoreId", cart.getProductStoreId());
            session.setAttribute("orderMode", cart.getOrderType());
            session.setAttribute("orderPartyId", cart.getOrderPartyId());
        } else {
            session.removeAttribute("productStoreId"); // TODO: REVIEW: is this removal safe for shop? it may be essential for orderentry...
            session.removeAttribute("orderMode");
            session.removeAttribute("orderPartyId");
        }
        return cart;
    }

    /**
     * SCIPIO: Sets the cart in session and request immediately as well as related cart attributes,
     * all inside a cart synchronized section (convenience method).
     * Added 2019-01. */
    public static ShoppingCart setSyncCartObjectAndAttr(HttpServletRequest request, ShoppingCart cart) {
        try (CartSync cartSync = CartSync.synchronizedSection(request)) { // SCIPIO
            cart = setCartObjectAndAttr(request, cart);
        }
        return cart;
    }

    /**
     * SCIPIO: Removes the cart in session and request immediately; same as <code>setCartObject(request, null)</code>.
     * NOTE: For synchronized updates, use {@link CartUpdate} instead of this directly; if you must use this,
     * it must always be wrapped in a {@link CartSync#synchronizedSection(HttpServletRequest)}.
     * NOTE: This automatically marks the cart as changed in the request, so do not reassign an unmodified cart.
     * Added 2018-11-20. */
    public static ShoppingCart removeCartObject(HttpServletRequest request, RequestVarScopes modifyScopesFilter) {
        return setCartObject(request, null, modifyScopesFilter);
    }

    /**
     * SCIPIO: Removes the cart in session and request immediately; same as <code>setCartObject(request, null)</code>.
     * NOTE: For synchronized updates, use {@link CartUpdate} instead of this directly; if you must use this,
     * it must always be wrapped in a {@link CartSync#synchronizedSection(HttpServletRequest)}.
     * NOTE: This automatically marks the cart as changed in the request, so do not reassign an unmodified cart.
     * Added 2018-11-20. */
    public static ShoppingCart removeCartObject(HttpServletRequest request) {
        return setCartObject(request, null, RequestVarScopes.ALL);
    }

    /**
     * SCIPIO: Returns true if the current cart was changed during this request 
     * (by CartUpdate or anything that sets "shoppingCartChanged").
     * <p>
     * Added 2018-11-30. 
     */
    public static boolean isCartChanged(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute("shoppingCartChanged"));
    }

    /**
     * SCIPIO: Marks the current cart has having been changed in the request. 
     * Added 2018-11-30. 
     */
    public static void markCartChanged(HttpServletRequest request) {
        request.setAttribute("shoppingCartChanged", true);
    }

    public static String switchCurrentCartObject(HttpServletRequest request, HttpServletResponse response) {
        try (CartSync cartSync = CartSync.synchronizedSection(request)) { // SCIPIO
        HttpSession session = request.getSession(true);
        String cartIndexStr = request.getParameter("cartIndex");
        int cartIndex = -1;
        if (UtilValidate.isNotEmpty(cartIndexStr) && UtilValidate.isInteger(cartIndexStr)) {
            try {
                cartIndex = Integer.parseInt(cartIndexStr);
            } catch (NumberFormatException nfe) {
                Debug.logWarning("Invalid value for cart index =" + cartIndexStr, module);
            }
        }
        List<ShoppingCart> cartList = UtilGenerics.checkList(session.getAttribute("shoppingCartList"));
        if (UtilValidate.isEmpty(cartList)) {
            cartList = new LinkedList<>();
            session.setAttribute("shoppingCartList", cartList);
        }
        ShoppingCart currentCart = (ShoppingCart) session.getAttribute("shoppingCart");
        if (currentCart != null) {
            cartList.add(currentCart);
            session.setAttribute("shoppingCartList", cartList);
            session.removeAttribute("shoppingCart");
        }
        ShoppingCart newCart = null;
        if (cartIndex >= 0 && cartIndex < cartList.size()) {
            newCart = cartList.remove(cartIndex);
        } else {
            String productStoreId = request.getParameter("productStoreId");
            if (UtilValidate.isNotEmpty(productStoreId)) {
                session.setAttribute("productStoreId", productStoreId);
            }
            newCart = getCartObject(request);
        }
        ShoppingCartEvents.setCartObject(request, newCart); // SCIPIO: Use setter: session.setAttribute("shoppingCart", newCart);
        }
        return "success";
    }

    public static String clearCartFromList(HttpServletRequest request, HttpServletResponse response) {
        try (CartSync cartSync = CartSync.synchronizedSection(request)) { // SCIPIO
        HttpSession session = request.getSession(true);
        String cartIndexStr = request.getParameter("cartIndex");
        int cartIndex = -1;
        if (UtilValidate.isNotEmpty(cartIndexStr) && UtilValidate.isInteger(cartIndexStr)) {
            try {
                cartIndex = Integer.parseInt(cartIndexStr);
            } catch (NumberFormatException nfe) {
                Debug.logWarning("Invalid value for cart index =" + cartIndexStr, module);
            }
        }
        List<ShoppingCart> cartList = UtilGenerics.checkList(session.getAttribute("shoppingCartList"));
        if (UtilValidate.isNotEmpty(cartList) && cartIndex >= 0 && cartIndex < cartList.size()) {
            cartList.remove(cartIndex);
        }
        }
        return "success";
    }

    /** SCIPIO: Preliminary setup to prepare cart for request. Added 2018-12-03. */
    public static String prepareCartForRequest(HttpServletRequest request, HttpServletResponse response) {
        // Prevent shoppingCart from being cached in view-last cached parameters
        RequestAttrNamePolicy.from(request).addExclude(RequestSavingAttrPolicy.NotSaveable.class, "shoppingCart");
        RequestAttrNamePolicy.from(request).addExclude(RequestSavingAttrPolicy.NotSaveable.class, "shoppingCartChanged");
        return "success";
    }

    /** SCIPIO: Extra cart cleaning after-logout event. Added 2018-12-03. */
    public static String cleanCartAfterLogout(HttpServletRequest request, HttpServletResponse response) {
        removeCartObject(request, RequestVarScopes.REQUEST); // SCIPIO: 2018-12-03: Ensure cart request attribute is removed
        return "success";
    }

    /** Update the cart's UserLogin object if it isn't already set. */
    public static String keepCartUpdated(HttpServletRequest request, HttpServletResponse response) {
        prepareCartForRequest(request, response); // SCIPIO

        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        ShoppingCart cart = getCartObject(request);
        Locale locale = UtilHttp.getLocale(request);

        // SCIPIO: Refactored for synchronized cart updates
        // NOTE: This uses double-checking to prevent cart modifications at every single call
        
        boolean modifyCart = false;
        
        // if we just logged in set the UL
        if (shouldCartLoginBeUpdated(session, cart.getUserLogin(), "userLogin", false) != null) { // SCIPIO: refactored
            modifyCart = true;
        }

        // same for autoUserLogin
        if (shouldCartLoginBeUpdated(session, cart.getAutoUserLogin(), "autoUserLogin", false) != null) { // SCIPIO: refactored
            modifyCart = true;
        }

        // update the locale
        if (cart.getLocale() == null || !locale.equals(cart.getLocale())) {
            modifyCart = true;
        }

        if (modifyCart) {
            // SCIPIO: This is the original code, wrapped in synchronization
            // NOTE: We re-run all the checks because we're re-fetching the cart instance inside block
            modifyCart = false;
            try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
            cart = cartUpdate.getCartForUpdate();

            try {
                // SCIPIO: FIXME?: In actuality the userLogin/autoUserLogin will get re-read here... for now no obvious issues
                // if we just logged in set the UL
                Optional<GenericValue> userLoginOpt = shouldCartLoginBeUpdated(session, cart.getUserLogin(), "userLogin", true);
                if (userLoginOpt != null) {
                    modifyCart = true;
                    GenericValue userLogin = userLoginOpt.orElse(null);
                    if (cart.getUserLogin() != null) {
                        if (Debug.infoOn()) {
                            Debug.logInfo("Replacing cart userLogin [userLoginId: " + cart.getUserLogin().getString("userLoginId")
                                    + ", partyId: " + cart.getUserLogin().getString("partyId") + "] with "
                                    + (userLogin == null ? "[null]" : "[userLoginId: " + userLogin.getString("userLoginId")
                                    + ", partyId: " + userLogin.getString("partyId") + "]"), module);
                        }
                    }
                    try {
                        cart.setUserLogin(userLogin, dispatcher);
                    } catch (CartItemModifyException e) {
                        Debug.logWarning(e, module);
                    }
                }
    
                // same for autoUserLogin
                // SCIPIO: Only treat bad autoUserLogin as a cart clear if there is no cart userLogin,
                // because autoUserLogin is ignored when userLogin is non-null
                boolean autoUserLoginClear = (cart.getUserLogin() == null);
                Optional<GenericValue> autoUserLoginOpt = shouldCartLoginBeUpdated(session, cart.getAutoUserLogin(), "autoUserLogin", autoUserLoginClear);
                if (autoUserLoginOpt != null) {
                    modifyCart = true;
                    GenericValue autoUserLogin = autoUserLoginOpt.orElse(null);
                    if (cart.getUserLogin() != null) {
                        if (Debug.infoOn()) {
                            Debug.logInfo("Replacing cart autoUserLogin [userLoginId: " + cart.getUserLogin().getString("userLoginId")
                                    + ", partyId: " + cart.getUserLogin().getString("partyId") + "] with "
                                    + (autoUserLogin == null ? "[null]" : "[userLoginId: " + autoUserLogin.getString("userLoginId")
                                    + ", partyId: " + autoUserLogin.getString("partyId") + "]"), module);
                        }
                    }
                    if (cart.getUserLogin() == null) {
                        try {
                            cart.setAutoUserLogin(autoUserLogin, dispatcher);
                        } catch (CartItemModifyException e) {
                            Debug.logWarning(e, module);
                        }
                    } else {
                        // SCIPIO
                        //cart.setAutoUserLogin(autoUserLogin);
                        cart.setAutoUserLoginAlways(autoUserLogin);
                    }
                }

                // update the locale
                if (cart.getLocale() == null || !locale.equals(cart.getLocale())) {
                    modifyCart = true;
                    cart.setLocale(locale);
                }
            } catch(CartUserInvalidException e) {
                Debug.logWarning("Invalid cart state: " + e.getMessage() + "; clearing cart", module);
                cart = ShoppingCartFactory.createWebShoppingCart(request); // SCIPIO: use factory
                modifyCart = true;
            }

            if (modifyCart) {
                cart = cartUpdate.commit(cart); // SCIPIO
            }
            }
        }

        return "success";
    }

    /**
     * SCIPIO: Checks if the given userLogin or autoUserLogin from cart should be updated, and returns non-null
     * with the new value if it should be changed (the value in the Optional may be null).
     * <p>
     * Refactored from {@link #keepCartUpdated(HttpServletRequest, HttpServletResponse)} 2018-11-27.
     */
    private static Optional<GenericValue> shouldCartLoginBeUpdated(HttpSession session, GenericValue cartUserLogin,
            String sessionUserLoginAttr, boolean noUserReplace) throws CartUserInvalidException {
        if (cartUserLogin == null) {
            GenericValue userLogin = (GenericValue) session.getAttribute(sessionUserLoginAttr);
            if (userLogin != null) {
                return Optional.ofNullable(userLogin);
            }
        } else {
            // SCIPIO: 2018-11-27: new case: especially when server restarts, it's possible for stored session cart to contain
            // "anonymous"; may also be possible to bypass UI logout in other ways.
            // In such case can either TRY to change the user or kill the whole cart - for now, try just switching the user...
            GenericValue userLogin = (GenericValue) session.getAttribute(sessionUserLoginAttr);
            if ("anonymous".equals(cartUserLogin.get("userLoginId"))) {
                if (userLogin != null && "anonymous".equals(userLogin.get("userLoginId")) 
                        && Objects.equals(userLogin.getString("partyId"), cartUserLogin.getString("partyId"))) {
                    return null; // avoid needless setUserLogin calls if anon user has not changed
                }
                // NOTE: For now we'll treat anonymous userLogin in cart the same as null;
                // but it is possible issues come from this w.r.t. addresses, cart options, etc.
                // so in the future this could be a CartUserInvalidException instead...
                return Optional.ofNullable(userLogin);
            }
            if (userLogin != null) {
                if (!Objects.equals(userLogin.get("userLoginId"), cartUserLogin.get("userLoginId"))) {
                    if (noUserReplace) {
                        throw new CartUserInvalidException("Cart user '" + cartUserLogin.get("userLoginId") 
                            + "' does not match logged-in user '" + userLogin.get("userLoginId")  + "'");
                    } else {
                        return Optional.ofNullable(userLogin);
                    }
                }
            } else {
                // If there is no login in session but cart has a login... this can't continue
                if (noUserReplace) {
                    throw new CartUserInvalidException("Cart contains a user (" + cartUserLogin.get("userLoginId")
                        + ") but there is no user logged-in");
                } else {
                    return Optional.ofNullable(userLogin);
                }
            }
        }
        return null;
    }
    
    @SuppressWarnings("serial")
    private static class CartUserInvalidException extends RuntimeException { // SCIPIO
        protected CartUserInvalidException(String message) {
            super(message);
        }
    }
    
    /** For GWP Promotions with multiple alternatives, selects an alternative to the current GWP */
    public static String setDesiredAlternateGwpProductId(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String alternateGwpProductId = request.getParameter("alternateGwpProductId");
        String alternateGwpLineStr = request.getParameter("alternateGwpLine");
        Locale locale = UtilHttp.getLocale(request);

        if (UtilValidate.isEmpty(alternateGwpProductId)) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderCouldNotSelectAlternateGiftNoAlternateGwpProductIdPassed", locale));
            return "error";
        }
        if (UtilValidate.isEmpty(alternateGwpLineStr)) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderCouldNotSelectAlternateGiftNoAlternateGwpLinePassed", locale));
            return "error";
        }

        int alternateGwpLine = 0;
        try {
            alternateGwpLine = Integer.parseInt(alternateGwpLineStr);
        } catch (Exception e) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderCouldNotSelectAlternateGiftAlternateGwpLineIsNotAValidNumber", locale));
            return "error";
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        ShoppingCartItem cartLine = cart.findCartItem(alternateGwpLine);
        if (cartLine == null) {
            request.setAttribute("_ERROR_MESSAGE_", "Could not select alternate gift, no cart line item found for #" + alternateGwpLine + ".");
            return "error";
        }

        if (cartLine.getIsPromo()) {
            // note that there should just be one promo adjustment, the reversal of the GWP, so use that to get the promo action key
            Iterator<GenericValue> checkOrderAdjustments = UtilMisc.toIterator(cartLine.getAdjustments());
            while (checkOrderAdjustments != null && checkOrderAdjustments.hasNext()) {
                GenericValue checkOrderAdjustment = checkOrderAdjustments.next();
                if (UtilValidate.isNotEmpty(checkOrderAdjustment.getString("productPromoId")) &&
                        UtilValidate.isNotEmpty(checkOrderAdjustment.getString("productPromoRuleId")) &&
                        UtilValidate.isNotEmpty(checkOrderAdjustment.getString("productPromoActionSeqId"))) {
                    GenericPK productPromoActionPk = delegator.makeValidValue("ProductPromoAction", checkOrderAdjustment).getPrimaryKey();
                    cart.setDesiredAlternateGiftByAction(productPromoActionPk, alternateGwpProductId);
                    if ("SALES_ORDER".equals(cart.getOrderType())) {
                        org.ofbiz.order.shoppingcart.product.ProductPromoWorker.doPromotions(cart, dispatcher);
                    }
                    cartUpdate.commit(cart); // SCIPIO
                    return "success";
                }
            }
        }

        }

        request.setAttribute("_ERROR_MESSAGE_", "Could not select alternate gift, cart line item found for #" + alternateGwpLine + " does not appear to be a valid promotional gift.");
        return "error";
    }

    /** Associates a party to order */
    public static String addAdditionalParty(HttpServletRequest request, HttpServletResponse response) {
        String partyId = request.getParameter("additionalPartyId");
        String roleTypeId[] = request.getParameterValues("additionalRoleTypeId");
        List<String> eventList = new LinkedList<>();
        Locale locale = UtilHttp.getLocale(request);
        int i;

        if (UtilValidate.isEmpty(partyId) || UtilValidate.isEmpty(roleTypeId) || roleTypeId.length < 1) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderPartyIdAndOrRoleTypeIdNotDefined", locale));
            return "error";
        }

        if (request.getAttribute("_EVENT_MESSAGE_LIST_") != null) {
            List<String> msg = UtilGenerics.checkList(request.getAttribute("_EVENT_MESSAGE_LIST_"));
            eventList.addAll(msg);
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        for (i = 0; i < roleTypeId.length; i++) {
            try {
                cart.addAdditionalPartyRole(partyId, roleTypeId[i]);
            } catch (Exception e) {
                eventList.add(e.getLocalizedMessage());
            }
        }

        cartUpdate.commit(cart); // SCIPIO
        }

        request.removeAttribute("_EVENT_MESSAGE_LIST_");
        request.setAttribute("_EVENT_MESSAGE_LIST_", eventList);

        return "success";
    }

    /** Removes a previously associated party to order */
    public static String removeAdditionalParty(HttpServletRequest request, HttpServletResponse response) {
        String partyId = request.getParameter("additionalPartyId");
        String roleTypeId[] = request.getParameterValues("additionalRoleTypeId");
        List<String> eventList = new LinkedList<>();
        Locale locale = UtilHttp.getLocale(request);
        int i;

        if (UtilValidate.isEmpty(partyId) || roleTypeId.length < 1) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderPartyIdAndOrRoleTypeIdNotDefined", locale));
            return "error";
        }

        if (request.getAttribute("_EVENT_MESSAGE_LIST_") != null) {
            List<String> msg = UtilGenerics.checkList(request.getAttribute("_EVENT_MESSAGE_LIST_"));
            eventList.addAll(msg);
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        for (i = 0; i < roleTypeId.length; i++) {
            try {
                cart.removeAdditionalPartyRole(partyId, roleTypeId[i]);
            } catch (Exception e) {
                Debug.logInfo(e.getLocalizedMessage(), module);
                eventList.add(e.getLocalizedMessage());
            }
        }

        cartUpdate.commit(cart); // SCIPIO
        }

        request.removeAttribute("_EVENT_MESSAGE_LIST_");
        request.setAttribute("_EVENT_MESSAGE_LIST_", eventList);
        return "success";
    }

    /**
     * This should be called to translate the error messages of the
     * <code>ShoppingCartHelper</code> to an appropriately formatted
     * <code>String</code> in the request object and indicate whether
     * the result was an error or not and whether the errors were
     * critical or not
     *
     * @param result    The result returned from the
     * <code>ShoppingCartHelper</code>
     * @param request The servlet request instance to set the error messages
     * in
     * @return one of NON_CRITICAL_ERROR, ERROR or NO_ERROR.
     */
    private static String processResult(Map<String, Object> result, HttpServletRequest request) {
        //Check for errors
        StringBuilder errMsg = new StringBuilder();
        if (result.containsKey(ModelService.ERROR_MESSAGE_LIST)) {
            List<String> errorMsgs = UtilGenerics.checkList(result.get(ModelService.ERROR_MESSAGE_LIST));
            Iterator<String> iterator = errorMsgs.iterator();
            errMsg.append("<ul>");
            while (iterator.hasNext()) {
                errMsg.append("<li>");
                errMsg.append(iterator.next());
                errMsg.append("</li>");
            }
            errMsg.append("</ul>");
        } else if (result.containsKey(ModelService.ERROR_MESSAGE)) {
            errMsg.append(result.get(ModelService.ERROR_MESSAGE));
            request.setAttribute("_ERROR_MESSAGE_", errMsg.toString());
        }

        //See whether there was an error
        if (errMsg.length() > 0) {
            request.setAttribute("_ERROR_MESSAGE_", errMsg.toString());
            if (result.get(ModelService.RESPONSE_MESSAGE).equals(ModelService.RESPOND_SUCCESS)) {
                return NON_CRITICAL_ERROR;
            }
            return ERROR;
        }
        return NO_ERROR;
    }

    /** Assign agreement **/
    public static String selectAgreement(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String agreementId = request.getParameter("agreementId");
        Map<String, Object> result;

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        result = cartHelper.selectAgreement(agreementId);
        if (ServiceUtil.isError(result)) {
           request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(result));
           return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Assign currency **/
    public static String setCurrency(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String currencyUomId = request.getParameter("currencyUomId");
        Map<String, Object> result;

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
        result = cartHelper.setCurrency(currencyUomId);
        if (ServiceUtil.isError(result)) {
           request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(result));
           return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * set the order name of the cart based on request.  right now will always return "success"
     *
     */
    public static String setOrderName(HttpServletRequest request, HttpServletResponse response) {
        String orderName = request.getParameter("orderName");

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        cart.setOrderName(orderName);

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * set the PO number of the cart based on request.  right now will always return "success"
     *
     */
    public static String setPoNumber(HttpServletRequest request, HttpServletResponse response) {
        String correspondingPoId = request.getParameter("correspondingPoId");

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        cart.setPoNumber(correspondingPoId);

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * Add an order term *
     */
    public static String addOrderTerm(HttpServletRequest request, HttpServletResponse response) {
        Locale locale = UtilHttp.getLocale(request);

        String termTypeId = request.getParameter("termTypeId");
        String termValueStr = request.getParameter("termValue");
        String termDaysStr = request.getParameter("termDays");
        String textValue = request.getParameter("textValue");
        String description = request.getParameter("description");

        GenericValue termType = null;
        Delegator delegator = (Delegator) request.getAttribute("delegator");

        BigDecimal termValue = null;
        Long termDays = null;

        if (UtilValidate.isEmpty(termTypeId)) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderOrderTermTypeIsRequired", locale));
            return "error";
        }

        try {
            termType = EntityQuery.use(delegator).from("TermType").where("termTypeId", termTypeId).queryOne();
        } catch (GenericEntityException gee) {
            request.setAttribute("_ERROR_MESSAGE_", gee.getMessage());
            return "error";
        }

        if (("FIN_PAYMENT_TERM".equals(termTypeId) && UtilValidate.isEmpty(termDaysStr)) || (UtilValidate.isNotEmpty(termType) && "FIN_PAYMENT_TERM".equals(termType.get("parentTypeId")) && UtilValidate.isEmpty(termDaysStr))) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderOrderTermDaysIsRequired", locale));
            return "error";
        }

        if (UtilValidate.isNotEmpty(termValueStr)) {
            try {
                termValue = new BigDecimal(termValueStr);
            } catch (NumberFormatException e) {
                request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderOrderTermValueError", UtilMisc.toMap("orderTermValue", termValueStr), locale));
                return "error";
            }
        }

        if (UtilValidate.isNotEmpty(termDaysStr)) {
            try {
                termDays = Long.valueOf(termDaysStr);
            } catch (NumberFormatException e) {
                request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "OrderOrderTermDaysError", UtilMisc.toMap("orderTermDays", termDaysStr), locale));
                return "error";
            }
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO

        removeOrderTerm(request, response);

        ShoppingCart cart = cartUpdate.getCartForUpdate(); // SCIPIO: moved to here from start of method

        cart.addOrderTerm(termTypeId, null, termValue, termDays, textValue, description);

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * Remove an order term *
     */
    public static String removeOrderTerm(HttpServletRequest request, HttpServletResponse response) {
        String termIndexStr = request.getParameter("termIndex");
        if (UtilValidate.isNotEmpty(termIndexStr)) {
            try {
                Integer termIndex = Integer.parseInt(termIndexStr);
                if (termIndex >= 0) {
                    try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
                    ShoppingCart cart = cartUpdate.getCartForUpdate();
  
                    List<GenericValue> orderTerms = cart.getOrderTerms();
                    if (orderTerms != null && orderTerms.size() > termIndex) {
                        cart.removeOrderTerm(termIndex);
                    }

                    cartUpdate.commit(cart); // SCIPIO
                    }
                }
            } catch (NumberFormatException e) {
                Debug.logWarning(e, "Error parsing termIndex: " + termIndexStr, module);
            }
        }

        return "success";
    }

    /** Initialize order entry from a shopping list **/
    public static String loadCartFromShoppingList(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");

        String shoppingListId = request.getParameter("shoppingListId");

        ShoppingCart cart = null;
        try {
            Map<String, Object> outMap = dispatcher.runSync("loadCartFromShoppingList",
                    UtilMisc.<String, Object>toMap("shoppingListId", shoppingListId,
                    "userLogin", userLogin));
            if (ServiceUtil.isError(outMap)) {
                String errorMessage = ServiceUtil.getErrorMessage(outMap);
                request.setAttribute("_ERROR_MESSAGE_", errorMessage);
                Debug.logError(errorMessage, module);
                return "error";
            }
            cart = (ShoppingCart)outMap.get("shoppingCart");
        } catch (GenericServiceException exc) {
            request.setAttribute("_ERROR_MESSAGE_", exc.getMessage());
            return "error";
        }

        setSyncCartObjectAndAttr(request, cart); // SCIPIO: refactored

        return "success";
    }

    /** Initialize order entry from a quote **/
    public static String loadCartFromQuote(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue)session.getAttribute("userLogin");

        String quoteId = request.getParameter("quoteId");

        ShoppingCart cart = null;
        try {
            Map<String, Object> outMap = dispatcher.runSync("loadCartFromQuote",
                    UtilMisc.<String, Object>toMap("quoteId", quoteId,
                            "applyQuoteAdjustments", "true",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(outMap)) {
                request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(outMap));
                return "error";
            }
            cart = (ShoppingCart) outMap.get("shoppingCart");
        } catch (GenericServiceException exc) {
            request.setAttribute("_ERROR_MESSAGE_", exc.getMessage());
            return "error";
        }

        // Set the cart's default checkout options for a quick checkout
        cart.setDefaultCheckoutOptions(dispatcher);
        // Make the cart read-only
        cart.setReadOnlyCart(true);

        setSyncCartObjectAndAttr(request, cart); // SCIPIO: refactored

        return "success";
    }

    /** Initialize order entry from an existing order **/
    public static String loadCartFromOrder(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue)session.getAttribute("userLogin");
        Delegator delegator = (Delegator) request.getAttribute("delegator");

        // SCIPIO: 06-08-20: Getting these two fields from request attributes when the come as null from request parameters.
        String orderId = request.getParameter("orderId");
        if (UtilValidate.isEmpty(orderId) && UtilValidate.isNotEmpty(request.getAttribute("orderId"))) {
            orderId = (String) request.getAttribute("orderId");
        }
        String createAsNewOrder = request.getParameter("createAsNewOrder");
        if (UtilValidate.isEmpty(createAsNewOrder) && UtilValidate.isNotEmpty(request.getAttribute("createAsNewOrder"))) {
            createAsNewOrder = (String) request.getAttribute("createAsNewOrder");
        }

        ShoppingCart cart = null;
        try {
            Map<String, Object> outMap = dispatcher.runSync("loadCartFromOrder",
                                                UtilMisc.<String, Object>toMap("orderId", orderId, "createAsNewOrder", createAsNewOrder,
                                                        "skipProductChecks", Boolean.TRUE, // the products have already been checked in the order, no need to check their validity again
                                                        "userLogin", userLogin));
            if (ServiceUtil.isError(outMap)) {
                request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(outMap));
                return "error";
            }
            cart = (ShoppingCart) outMap.get("shoppingCart");

            cart.removeAdjustmentByType("SALES_TAX");
            cart.removeAdjustmentByType("VAT_TAX");
            cart.removeAdjustmentByType("VAT_PRICE_CORRECT");
            cart.removeAdjustmentByType("PROMOTION_ADJUSTMENT");
            String shipGroupSeqId = null;
            long groupIndex = cart.getShipInfoSize();
            List<GenericValue> orderAdjustmentList = new ArrayList<>();
            List<GenericValue> orderAdjustments = cart.getAdjustments();
            try {
                orderAdjustmentList = EntityQuery.use(delegator).from("OrderAdjustment").where("orderId", orderId).queryList();
            } catch (GenericEntityException e) {
                Debug.logError(e, module);
            }
            for (long itr = 1; itr <= groupIndex; itr++) {
                shipGroupSeqId = UtilFormatOut.formatPaddedNumber(itr, 5);
                List<GenericValue> duplicateAdjustmentList = new ArrayList<>();
                for (GenericValue adjustment: orderAdjustmentList) {
                    if ("PROMOTION_ADJUSTMENT".equals(adjustment.get("orderAdjustmentTypeId"))) {
                        cart.addAdjustment(adjustment);
                    }
                    if ("SALES_TAX".equals(adjustment.get("orderAdjustmentTypeId"))) {
                        if (adjustment.get("description") != null
                                    && ((String)adjustment.get("description")).startsWith("Tax adjustment due")) {
                                cart.addAdjustment(adjustment);
                            }
                        if ("Y".equals(adjustment.getString("isManual"))) {
                            cart.addAdjustment(adjustment);
                        }
                    }
                }
                for (GenericValue orderAdjustment: orderAdjustments) {
                    if ("OrderAdjustment".equals(orderAdjustment.getEntityName())) {
                        if (("SHIPPING_CHARGES".equals(orderAdjustment.get("orderAdjustmentTypeId"))) &&
                                orderAdjustment.get("orderId").equals(orderId) &&
                                orderAdjustment.get("shipGroupSeqId").equals(shipGroupSeqId) && orderAdjustment.get("comments") == null) {
                            // Removing objects from list for old Shipping and Handling Charges Adjustment and Sales Tax Adjustment.
                            duplicateAdjustmentList.add(orderAdjustment);
                        }
                    }
                }
                orderAdjustments.removeAll(duplicateAdjustmentList);
            }
        } catch (GenericServiceException exc) {
            request.setAttribute("_ERROR_MESSAGE_", exc.getMessage());
            return "error";
        }

        cart.setAttribute("addpty", "Y");

        // SCIPIO: do this before attribute assign, for thread safety
        // Since we only need the cart items, so set the order id as null
        cart.setOrderId(null);

        setSyncCartObjectAndAttr(request, cart); // SCIPIO: refactored

        // SCIPIO: this is moved before the session.setAttribute for thread safety reasons
        //// Since we only need the cart items, so set the order id as null
        //cart.setOrderId(null);
        return "success";
    }

    public static String createQuoteFromCart(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue)session.getAttribute("userLogin");
        String destroyCart = request.getParameter("destroyCart");

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        Map<String, Object> result = null;
        String quoteId = null;
        try {
            result = dispatcher.runSync("createQuoteFromCart",
                    UtilMisc.toMap("cart", cart,
                            "userLogin", userLogin));
            quoteId = (String) result.get("quoteId");
        } catch (GenericServiceException exc) {
            request.setAttribute("_ERROR_MESSAGE_", exc.getMessage());
            return "error";
        }
        if (ServiceUtil.isError(result)) {
           request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(result));
           return "error";
        }
        request.setAttribute("quoteId", quoteId);
        if (destroyCart != null && "Y".equals(destroyCart)) {
            ShoppingCartEvents.destroyCart(request, response);
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * SCIPIO (2019-02-25): Create customer requests. This works along createCustRequestFromCart but for the rest of requests types (not RfQ)
     * @param request
     * @param response
     * @return
     */
    public static String createCustRequest(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
        TimeZone timeZone = (TimeZone) request.getAttribute("timeZone");
        Locale locale = (Locale) request.getAttribute("locale");
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);
        String productStoreId = ProductStoreWorker.getProductStoreId(request);

        Map<String, Object> result = null;
        String custRequestId = null;
        try {
            Map<String, Object> createCustRequestCtx = ServiceUtil.setServiceFields(dispatcher, "createCustRequest", paramMap, userLogin, timeZone, locale);
            createCustRequestCtx.put("statusId", "CRQ_SUBMITTED");
            createCustRequestCtx.put("fromPartyId", userLogin.getString("partyId"));
            createCustRequestCtx.put("salesChannelEnumId", "WEB_SALES_CHANNEL");
            createCustRequestCtx.put("openDateTime", UtilDateTime.nowTimestamp());
            createCustRequestCtx.put("productStoreId", productStoreId);
            result = dispatcher.runSync("createCustRequest", createCustRequestCtx);
            custRequestId = (String) result.get("custRequestId");
        } catch (Exception exc) {
            request.setAttribute("_ERROR_MESSAGE_", exc.getMessage());
            return "error";
        }
        if (ServiceUtil.isError(result)) {
            request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(result));
            return "error";
        }
        request.setAttribute("custRequestId", custRequestId);

        return "success";
    }

    public static String createCustRequestFromCart(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        HttpSession session = request.getSession();
        GenericValue userLogin = (GenericValue)session.getAttribute("userLogin");
        String destroyCart = request.getParameter("destroyCart");

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        Map<String, Object> result = null;
        String custRequestId = null;
        try {
            result = dispatcher.runSync("createCustRequestFromCart",
                    UtilMisc.toMap("cart", cart,
                            "userLogin", userLogin));
            custRequestId = (String) result.get("custRequestId");
        } catch (GenericServiceException exc) {
            request.setAttribute("_ERROR_MESSAGE_", exc.getMessage());
            return "error";
        }
        if (ServiceUtil.isError(result)) {
           request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(result));
           return "error";
        }
        request.setAttribute("custRequestId", custRequestId);
        if (destroyCart != null && "Y".equals(destroyCart)) {
            ShoppingCartEvents.destroyCart(request, response);
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Initialize order entry **/
    public static String initializeOrderEntry(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        HttpSession session = request.getSession();
        Security security = (Security) request.getAttribute("security");
        GenericValue userLogin = (GenericValue)session.getAttribute("userLogin");
        Locale locale = UtilHttp.getLocale(request);

        String productStoreId = request.getParameter("productStoreId");

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        if (UtilValidate.isNotEmpty(productStoreId)) {
            session.setAttribute("productStoreId", productStoreId);
        }

        // TODO: re-factor and move this inside the ShoppingCart constructor
        String orderMode = request.getParameter("orderMode");
        if (orderMode != null) {
            cart.setOrderType(orderMode);
            session.setAttribute("orderMode", orderMode);
        } else {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderPleaseSelectEitherSaleOrPurchaseOrder", locale));
            return "error";
        }

        // check the selected product store
        GenericValue productStore = null;
        if (UtilValidate.isNotEmpty(productStoreId)) {
            productStore = ProductStoreWorker.getProductStore(productStoreId, delegator);
            if (productStore != null) {

                // check permission for taking the order
                boolean hasPermission = false;
                if (("PURCHASE_ORDER".equals(cart.getOrderType())) && (security.hasEntityPermission("ORDERMGR", "_PURCHASE_CREATE", request))) { // SCIPIO: Now using request; was: session
                    hasPermission = true;
                } else if ("SALES_ORDER".equals(cart.getOrderType())) {
                    if (security.hasEntityPermission("ORDERMGR", "_SALES_CREATE", request)) { // SCIPIO: Now using request; was: session
                        hasPermission = true;
                    } else {
                        // if the user is a rep of the store, then he also has permission
                        List<GenericValue> storeReps = null;
                        try {
                            storeReps = EntityQuery.use(delegator).from("ProductStoreRole")
                                    .where("productStoreId", productStore.getString("productStoreId"), "partyId", userLogin.getString("partyId"), "roleTypeId", "SALES_REP")
                                    .filterByDate()
                                    .queryList();
                        } catch (GenericEntityException gee) {
                            request.setAttribute("_ERROR_MESSAGE_", gee.getMessage());
                            return "error";
                        }
                        if (UtilValidate.isNotEmpty(storeReps)) {
                            hasPermission = true;
                        }
                    }
                }

                if (hasPermission) {
                    // SCIPIO: 2018-11-18: This only crushes the cart needlessly
                    //cart = ShoppingCartEvents.getCartObject(request, null, productStore.getString("defaultCurrencyUomId"));
                    String currencyUom = productStore.getString("defaultCurrencyUomId");
                    if (currencyUom != null && !currencyUom.equals(cart.getCurrency())) {
                        try {
                            cart.setCurrency((LocalDispatcher) request.getAttribute("dispatcher"), currencyUom);
                        } catch (CartItemModifyException e) {
                            Debug.logWarning(e, "Could not set cart currency", module);
                        }
                    }
                } else {
                    request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderYouDoNotHavePermissionToTakeOrdersForThisStore", locale));
                    cart.clear();
                    session.removeAttribute("orderMode");
                    return "error";
                }
                cart.setProductStoreId(productStoreId);
            } else {
                cart.setProductStoreId(null);
            }
        }

        // SCIPIO: Set webSiteId if applicable (by default, do NOT set it here - it is done by setOrderCurrencyAgreementShipDatesForOrderEntry; but screen may request it here instead)
        if (UtilMisc.booleanValueVersatile(request.getParameter("initCartWebSiteId"), false)) {
            setCartWebSiteIdForOrderEntry(request, response, cart);
        }

        if ("SALES_ORDER".equals(cart.getOrderType()) && UtilValidate.isEmpty(cart.getProductStoreId())) {
            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderAProductStoreMustBeSelectedForASalesOrder", locale));
            cart.clear();
            session.removeAttribute("orderMode");
            return "error";
        }

        String salesChannelEnumId = request.getParameter("salesChannelEnumId");
        if (UtilValidate.isNotEmpty(salesChannelEnumId)) {
            cart.setChannelType(salesChannelEnumId);
        }

        // set party info
        String partyId = request.getParameter("supplierPartyId");
        cart.setSupplierPartyId(partyId);
        String originOrderId = request.getParameter("originOrderId");
        cart.setAttribute("originOrderId", originOrderId);

        if (UtilValidate.isNotEmpty(request.getParameter("partyId"))) {
            partyId = request.getParameter("partyId");
        }
        String userLoginId = request.getParameter("userLoginId");
        if (partyId != null || userLoginId != null) {
            if (UtilValidate.isEmpty(partyId) && UtilValidate.isNotEmpty(userLoginId)) {
                GenericValue thisUserLogin = null;
                try {
                    thisUserLogin = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", userLoginId).queryOne();
                } catch (GenericEntityException gee) {
                    request.setAttribute("_ERROR_MESSAGE_", gee.getMessage());
                    return "error";
                }
                if (thisUserLogin != null) {
                    partyId = thisUserLogin.getString("partyId");
                } else {
                    partyId = userLoginId;
                }
            }
            if (UtilValidate.isNotEmpty(partyId)) {
                GenericValue thisParty = null;
                try {
                    thisParty = EntityQuery.use(delegator).from("Party").where("partyId", partyId).queryOne();
                } catch (GenericEntityException gee) {
                    request.setAttribute("_ERROR_MESSAGE_", gee.getMessage());
                    return "error";
                }
                if (thisParty == null) {
                    request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderCouldNotLocateTheSelectedParty", locale));
                    return "error";
                }
                cart.setOrderPartyId(partyId);
                if ("PURCHASE_ORDER".equals(cart.getOrderType())) {
                    cart.setBillFromVendorPartyId(partyId);
                }
            } else if (partyId != null && partyId.length() == 0) {
                cart.setOrderPartyId("_NA_");
                partyId = null;
            }
        } else {
            partyId = cart.getPartyId();
            if (partyId != null && "_NA_".equals(partyId)) {
                partyId = null;
            }
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /** Route order entry **/
    public static String routeOrderEntry(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();

        // if the order mode is not set in the attributes, then order entry has not been initialized
        if (session.getAttribute("orderMode") == null) {
            return "init";
        }

        // if the request is coming from the init page, then orderMode will be in the request parameters
        if (request.getParameter("orderMode") != null) {
            return "agreements"; // next page after init is always agreements
        }

        // orderMode is set and there is an order in progress, so go straight to the cart
        return "cart";
    }

    public static String doManualPromotions(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        List<GenericValue> manualPromotions = new LinkedList<>();

        // iterate through the context and find all keys that start with "productPromoId_"
        Map<String, Object> context = UtilHttp.getParameterMap(request);
        String keyPrefix = "productPromoId_";
        for (int i = 1; i <= 50; i++) {
            String productPromoId = (String)context.get(keyPrefix + i);
            if (UtilValidate.isNotEmpty(productPromoId)) {
                try {
                    GenericValue promo = EntityQuery.use(delegator).from("ProductPromo").where("productPromoId", productPromoId).queryOne();
                    if (promo != null) {
                        manualPromotions.add(promo);
                    }
                } catch (GenericEntityException gee) {
                    request.setAttribute("_ERROR_MESSAGE_", gee.getMessage());
                    return "error";
                }
            } else {
                break;
            }
        }

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();

        ProductPromoWorker.doPromotions(cart, manualPromotions, dispatcher);

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }
    
    public static String bulkAddProducts(HttpServletRequest request, HttpServletResponse response) {
        Delegator delegator = (Delegator) request.getAttribute("delegator");
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String controlDirective = null;
        Map<String, Object> result = null;
        String productId = null;
        String productCategoryId = null;
        String quantityStr = null;
        String itemDesiredDeliveryDateStr = null;
        BigDecimal quantity = BigDecimal.ZERO;
        String catalogId = CatalogWorker.getCurrentCatalogId(request);
        String itemType = null;
        String itemDescription = "";

        // Get the parameters as a MAP, remove the productId and quantity params.
        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);

        String itemGroupNumber = request.getParameter("itemGroupNumber");

        // Get shoppingList info if passed.  I think there can only be one shoppingList per request
        String shoppingListId = request.getParameter("shoppingListId");
        String shoppingListItemSeqId = request.getParameter("shoppingListItemSeqId");

        // The number of multi form rows is retrieved
        int rowCount = UtilHttp.getMultiFormRowCount(paramMap);
        if (rowCount < 1) {
            Debug.logWarning("No rows to process, as rowCount = " + rowCount, module);
        } else {
            try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
            ShoppingCart cart = cartUpdate.getCartForUpdate();
            ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);

            for (int i = 0; i < rowCount; i++) {
                controlDirective = null;                // re-initialize each time
                String thisSuffix = UtilHttp.getMultiRowDelimiter() + i;        // current suffix after each field id

                // get the productId
                if (paramMap.containsKey("productId" + thisSuffix)) {
                    productId = (String) paramMap.remove("productId" + thisSuffix);
                }

                if (paramMap.containsKey("quantity" + thisSuffix)) {
                    quantityStr = (String) paramMap.remove("quantity" + thisSuffix);
                }
                if ((quantityStr == null) || (quantityStr.equals(""))) {    // otherwise, every empty value causes an exception and makes the log ugly
                    quantityStr = "0";  // default quantity is 0, so without a quantity input, this field will not be added
                }

                // parse the quantity
                try {
                    quantity = new BigDecimal(quantityStr);
                } catch (Exception e) {
                    Debug.logWarning(e, "Problems parsing quantity string: " + quantityStr, module);
                    quantity = BigDecimal.ZERO;
                }

                try {
                    //For quantity we should test if we allow to add decimal quantity for this product an productStore :
                    // if not and if quantity is in decimal format then return error.
                    if(! ProductWorker.isDecimalQuantityOrderAllowed(delegator, productId, cart.getProductStoreId())){
                        BigDecimal remainder = quantity.remainder(BigDecimal.ONE);
                        if (remainder.compareTo(BigDecimal.ZERO) != 0) {
                            request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error, "cart.addToCart.quantityInDecimalNotAllowed", cart.getLocale()));
                            return "error";
                        }
                        quantity = quantity.setScale(0, UtilNumber.getRoundingMode("order.rounding"));
                    }
                    else {
                        quantity = quantity.setScale(UtilNumber.getBigDecimalScale("order.decimals"), UtilNumber.getRoundingMode("order.rounding"));
                    }
                } catch (GenericEntityException e) {
                    Debug.logWarning(e.getMessage(), module);
                    quantity = BigDecimal.ONE;
                }

                // get the selected amount
                String selectedAmountStr = null;
                if (paramMap.containsKey("amount" + thisSuffix)) {
                    selectedAmountStr = (String) paramMap.remove("amount" + thisSuffix);
                }

                // parse the amount
                BigDecimal amount = null;
                if (UtilValidate.isNotEmpty(selectedAmountStr)) {
                    try {
                        amount = new BigDecimal(selectedAmountStr);
                    } catch (Exception e) {
                        Debug.logWarning(e, "Problem parsing amount string: " + selectedAmountStr, module);
                        amount = null;
                    }
                } else {
                    amount = BigDecimal.ZERO;
                }

                if (paramMap.containsKey("itemDesiredDeliveryDate" + thisSuffix)) {
                    itemDesiredDeliveryDateStr = (String) paramMap.remove("itemDesiredDeliveryDate" + thisSuffix);
                }
                // get the item type
                if (paramMap.containsKey("itemType" + thisSuffix)) {
                    itemType = (String) paramMap.remove("itemType" + thisSuffix);
                }

                if (paramMap.containsKey("itemDescription" + thisSuffix)) {
                    itemDescription = (String) paramMap.remove("itemDescription" + thisSuffix);
                }

                Map<String, Object> itemAttributes = UtilMisc.<String, Object>toMap("itemDesiredDeliveryDate", itemDesiredDeliveryDateStr);

                if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                    Debug.logInfo("Attempting to add to cart with productId = " + productId + ", categoryId = " + productCategoryId +
                            ", quantity = " + quantity + ", itemType = " + itemType + " and itemDescription = " + itemDescription, module);
                    result = cartHelper.addToCart(catalogId, shoppingListId, shoppingListItemSeqId, productId,
                                                  productCategoryId, itemType, itemDescription, null,
                                                  amount, quantity, null, null, null, null, null, null,
                                                  itemGroupNumber, itemAttributes,null);
                    // no values for price and paramMap (a context for adding attributes)
                    controlDirective = processResult(result, request);
                    if (controlDirective.equals(ERROR)) {    // if the add to cart failed, then get out of this loop right away
                        return "error";
                    }
                }
            }

            cartUpdate.commit(cart); // SCIPIO

            // Determine where to send the browser
            return cart.viewCartOnAdd() ? "viewcart" : "success";
            }
        }

        ShoppingCart cart = ShoppingCartEvents.getCartObject(request); // SCIPIO: fetch cart for non-modifying case
        return cart.viewCartOnAdd() ? "viewcart" : "success";
    }

    // request method for setting the currency, agreement, OrderId and shipment dates at once
    public static String setOrderCurrencyAgreementShipDates(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        Delegator delegator = (Delegator) request.getAttribute("delegator");

        String agreementId = request.getParameter("agreementId");
        String currencyUomId = request.getParameter("currencyUomId");
        String workEffortId = request.getParameter("workEffortId");
        String shipBeforeDateStr = request.getParameter("shipBeforeDate");
        String shipAfterDateStr = request.getParameter("shipAfterDate");
        String cancelBackOrderDateStr = request.getParameter("cancelBackOrderDate");
        String orderId = request.getParameter("orderId");
        String orderName = request.getParameter("orderName");
        String correspondingPoId = request.getParameter("correspondingPoId");
        Locale locale = UtilHttp.getLocale(request);
        Map<String, Object> result = null;

        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
        ShoppingCart cart = cartUpdate.getCartForUpdate();
        ShoppingCartHelper cartHelper = ShoppingCartFactory.createWebShoppingCartHelper(delegator, dispatcher, cart);
            
        // set the agreement if specified otherwise set the currency
        if (UtilValidate.isNotEmpty(agreementId)) {
            result = cartHelper.selectAgreement(agreementId);
        }
        if (UtilValidate.isNotEmpty(cart.getCurrency()) && UtilValidate.isNotEmpty(currencyUomId)) {
            result = cartHelper.setCurrency(currencyUomId);
        }
        if (ServiceUtil.isError(result)) {
            request.setAttribute("_ERROR_MESSAGE_", ServiceUtil.getErrorMessage(result));
            return "error";
        }

        // set the work effort id
        cart.setWorkEffortId(workEffortId);

        // set the order id if given
        if (UtilValidate.isNotEmpty(orderId)) {
            GenericValue thisOrder = null;
            try {
                thisOrder = EntityQuery.use(delegator).from("OrderHeader").where("orderId", orderId).queryOne();
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
            }
            if (thisOrder == null) {
                cart.setOrderId(orderId);
            } else {
                request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"OrderIdAlreadyExistsPleaseChooseAnother", locale));
                return "error";
            }
        }

        // set the order name
        cart.setOrderName(orderName);

        // set the corresponding purchase order id
        cart.setPoNumber(correspondingPoId);

        // set the default ship before and after dates if supplied
        try {
            if (UtilValidate.isNotEmpty(shipBeforeDateStr)) {
                if (shipBeforeDateStr.length() == 10) {
                    shipBeforeDateStr += " 00:00:00.000";
                }
                cart.setDefaultShipBeforeDate(java.sql.Timestamp.valueOf(shipBeforeDateStr));
            }
            if (UtilValidate.isNotEmpty(shipAfterDateStr)) {
                if (shipAfterDateStr.length() == 10) {
                    shipAfterDateStr += " 00:00:00.000";
                }
                cart.setDefaultShipAfterDate(java.sql.Timestamp.valueOf(shipAfterDateStr));
            }
            if (UtilValidate.isNotEmpty(cancelBackOrderDateStr)) {
                if (cancelBackOrderDateStr.length() == 10) {
                    cancelBackOrderDateStr += " 00:00:00.000";
                }
                cart.setCancelBackOrderDate(java.sql.Timestamp.valueOf(cancelBackOrderDateStr));
            }
        } catch (IllegalArgumentException e) {
            request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
            return "error";
        }

        cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    /**
     * SCIPIO: A version of setOrderCurrencyAgreementShipDates only for order entry.
     */
    public static String setOrderCurrencyAgreementShipDatesForOrderEntry(HttpServletRequest request, HttpServletResponse response) {
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) { // SCIPIO
            ShoppingCart cart = cartUpdate.getCartForUpdate();

            String result = setOrderCurrencyAgreementShipDates(request, response);
            if (!"success".equals(result)) {
                return result;
            }

            // SCIPIO: Set webSiteId if applicable (by default, set it here)
            if (UtilMisc.booleanValueVersatile(request.getParameter("setCartWebSiteId"), true)) {
                setCartWebSiteIdForOrderEntry(request, response, cart);
            }

            cartUpdate.commit(cart); // SCIPIO
        }
        return "success";
    }

    public static String setCartWebSiteIdForOrderEntry(HttpServletRequest request, HttpServletResponse response) { // SCIPIO
        try (CartUpdate cartUpdate = CartUpdate.updateSection(request)) {
            ShoppingCart cart = cartUpdate.getCartForUpdate();
            String result = setCartWebSiteIdForOrderEntry(request, response, cart);
            cartUpdate.commit(cart);
            return result;
        }
    }

    public static String setCartWebSiteIdForOrderEntry(HttpServletRequest request, HttpServletResponse response, ShoppingCart cart) { // SCIPIO
        String cartWebSiteId = request.getParameter("cartWebSiteId");
        if (cartWebSiteId != null) { // NOTE: May be empty string, which means explicit none (don't do default)
            // TODO?: Verify website is valid (not a real security concern in backend, for now)
            cart.setWebSiteId(cartWebSiteId.isEmpty() ? null : cartWebSiteId);
        } else {
            // SCIPIO: If cartWebSiteId not set as an option and this is a sales order, by default we'll use the default webSiteId for the order
            boolean useDefaultCartWebSiteId = UtilMisc.booleanValueVersatile(request.getParameter("useDefaultCartWebSiteId"), "SALES_ORDER".equals(cart.getOrderType()));
            if (useDefaultCartWebSiteId) {
                cartWebSiteId = ProductStoreWorker.getStoreDefaultWebSiteId((Delegator) request.getAttribute("delegator"), cart.getProductStoreId(), false);
                cart.setWebSiteId(cartWebSiteId);
            } else {
                cart.setWebSiteId(null);
            }
        }
        return "success";
    }

    public static String getConfigDetailsEvent(HttpServletRequest request, HttpServletResponse response) {

        Delegator delegator = (Delegator) request.getAttribute("delegator");
        String productId = request.getParameter("product_id");
        String currencyUomId = ShoppingCartEvents.getCartObject(request).getCurrency();
        ProductConfigWrapper configWrapper = ProductConfigWorker.getProductConfigWrapper(productId, currencyUomId, request);
        if (configWrapper == null) {
            Debug.logWarning("configWrapper is null", module);
            request.setAttribute("_ERROR_MESSAGE_", "configWrapper is null");
            return "error";
        }
        ProductConfigWorker.fillProductConfigWrapper(configWrapper, request);
        if (configWrapper.isCompleted()) {
            ProductConfigWorker.storeProductConfigWrapper(configWrapper, delegator);
            request.setAttribute("configId", configWrapper.getConfigId());
        }

        request.setAttribute("totalPrice", org.ofbiz.base.util.UtilFormatOut.formatCurrency(configWrapper.getTotalPrice(), currencyUomId, UtilHttp.getLocale(request)));
        return "success";
    }

    public static String bulkAddProductsInApprovedOrder(HttpServletRequest request, HttpServletResponse response) {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        Locale locale = UtilHttp.getLocale(request);
        String productId = null;
        String productCategoryId = null;
        String quantityStr = null;
        String itemDesiredDeliveryDateStr = null;
        BigDecimal quantity;
        String itemType = null;
        String itemDescription = "";
        String orderId = null;
        String shipGroupSeqId = null;

        Map<String, Object> paramMap = UtilHttp.getParameterMap(request);
        int rowCount = UtilHttp.getMultiFormRowCount(paramMap);
        if (rowCount < 1) {
            Debug.logWarning("No rows to process, as rowCount = " + rowCount, module);
        } else {
            for (int i = 0; i < rowCount; i++) {
                String thisSuffix = UtilHttp.getMultiRowDelimiter() + i;
                if (paramMap.containsKey("productId" + thisSuffix)) {
                    productId = (String) paramMap.remove("productId" + thisSuffix);
                }
                if (paramMap.containsKey("quantity" + thisSuffix)) {
                    quantityStr = (String) paramMap.remove("quantity" + thisSuffix);
                }
                if ((quantityStr == null) || (quantityStr.equals(""))) {
                    quantityStr = "0";
                }
                try {
                    quantity = new BigDecimal(quantityStr);
                } catch (Exception e) {
                    Debug.logWarning(e, "Problems parsing quantity string: " + quantityStr, module);
                    quantity = BigDecimal.ZERO;
                }
                String selectedAmountStr = null;
                if (paramMap.containsKey("amount" + thisSuffix)) {
                    selectedAmountStr = (String) paramMap.remove("amount" + thisSuffix);
                }
                BigDecimal amount = null;
                if (UtilValidate.isNotEmpty(selectedAmountStr)) {
                    try {
                        amount = new BigDecimal(selectedAmountStr);
                    } catch (Exception e) {
                        Debug.logWarning(e, "Problem parsing amount string: " + selectedAmountStr, module);
                        amount = null;
                    }
                } else {
                    amount = BigDecimal.ZERO;
                }
                if (paramMap.containsKey("itemDesiredDeliveryDate" + thisSuffix)) {
                    itemDesiredDeliveryDateStr = (String) paramMap.remove("itemDesiredDeliveryDate" + thisSuffix);
                }
                Timestamp itemDesiredDeliveryDate = null;
                if (UtilValidate.isNotEmpty(itemDesiredDeliveryDateStr)) {
                    try {
                        itemDesiredDeliveryDate = Timestamp.valueOf(itemDesiredDeliveryDateStr);
                    } catch (Exception e) {
                        Debug.logWarning(e,"Problems parsing Reservation start string: " + itemDesiredDeliveryDateStr, module);
                        itemDesiredDeliveryDate = null;
                        request.setAttribute("_ERROR_MESSAGE_", UtilProperties.getMessage(resource_error,"shoppingCartEvents.problem_parsing_item_desiredDeliveryDate_string", locale));
                    }
                }
                if (paramMap.containsKey("itemType" + thisSuffix)) {
                    itemType = (String) paramMap.remove("itemType" + thisSuffix);
                }
                if (paramMap.containsKey("itemDescription" + thisSuffix)) {
                    itemDescription = (String) paramMap.remove("itemDescription" + thisSuffix);
                }
                if (paramMap.containsKey("orderId" + thisSuffix)) {
                    orderId = (String) paramMap.remove("orderId" + thisSuffix);
                }
                if (paramMap.containsKey("shipGroupSeqId" + thisSuffix)) {
                    shipGroupSeqId = (String) paramMap.remove("shipGroupSeqId" + thisSuffix);
                }
                if (quantity.compareTo(BigDecimal.ZERO) > 0) {
                    Debug.logInfo("Attempting to add to cart with productId = " + productId + ", categoryId = " + productCategoryId +
                            ", quantity = " + quantity + ", itemType = " + itemType + " and itemDescription = " + itemDescription, module);
                    HttpSession session = request.getSession();
                    GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");
                    Map<String, Object> appendOrderItemMap = new HashMap<>();
                    appendOrderItemMap.put("productId", productId);
                    appendOrderItemMap.put("quantity", quantity);
                    appendOrderItemMap.put("orderId", orderId);
                    appendOrderItemMap.put("userLogin", userLogin);
                    appendOrderItemMap.put("amount", amount);
                    appendOrderItemMap.put("itemDesiredDeliveryDate", itemDesiredDeliveryDate);
                    appendOrderItemMap.put("shipGroupSeqId", shipGroupSeqId);
                    try {
                        Map<String, Object> result = dispatcher.runSync("appendOrderItem", appendOrderItemMap);
                        if (ServiceUtil.isError(result)) {
                            String errorMessage = ServiceUtil.getErrorMessage(result);
                            request.setAttribute("_ERROR_MESSAGE_", errorMessage);
                            Debug.logError(errorMessage, module);
                            return "error";
                        }
                        // SCIPIO: 2018-12-03: TODO: REVIEW: this stock code no longer works at all because
                        // destroyCart is now in a CartUpdate section and those sections must always prioritize
                        // the session variable first (to prevent lost updates from other threads).
                        // I'm not sure this code even did much useful originally, but just in case, we can at least
                        // call clear() on the cart (no sync/update section required because this is not the session cart)
                        //request.setAttribute("shoppingCart", result.get("shoppingCart"));
                        //ShoppingCartEvents.destroyCart(request, response);
                        ShoppingCart cart = (ShoppingCart) result.get("shoppingCart");
                        if (cart != null) {
                            cart.clear();
                        }
                    } catch (GenericServiceException e) {
                        Debug.logError(e, "Failed to execute service appendOrderItem", module);
                        request.setAttribute("_ERROR_MESSAGE_", e.getMessage());
                        return "error";
                    }
                }
            }
        }
        request.setAttribute("orderId", orderId);
        return  "success";
    }
}
