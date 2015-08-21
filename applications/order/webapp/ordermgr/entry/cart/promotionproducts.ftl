<#--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

<#if productIds?has_content>
    <@section title="${uiLabelMap.OrderProductsForPromotion}">
        <#if (listSize > 0)>
            <@table type="fields" border="0" width="100%" cellpadding="2">
                <@tr>
                <@td align="right">
                    <b>
                    <#if (viewIndex > 0)>
                    <a href="<@ofbizUrl>showPromotionDetails?productPromoId=${productPromoId!}&amp;VIEW_SIZE=${viewSize}&amp;VIEW_INDEX=${viewIndex-1}</@ofbizUrl>" class="${styles.button_default!}">${uiLabelMap.CommonPrevious}</a> |
                    </#if>
                    ${lowIndex+1} - ${highIndex} ${uiLabelMap.CommonOf} ${listSize}
                    <#if (listSize > highIndex)>
                    | <a href="<@ofbizUrl>showPromotionDetails?productPromoId=${productPromoId!}&amp;VIEW_SIZE=${viewSize}&amp;VIEW_INDEX=${viewIndex+1}</@ofbizUrl>" class="${styles.button_default!}">${uiLabelMap.CommonNext}</a>
                    </#if>
                    </b>
                </@td>
                </@tr>
            </@table>
        </#if>

        <@table type="data" autoAltRows=false width="100%" border="0" cellspacing="0" cellpadding="0" class="boxbottom">
          <@tr>
            <@td>${uiLabelMap.CommonQualifier}</@td>
            <@td>${uiLabelMap.CommonBenefit}</@td>
            <@td>&nbsp;</@td>
          </@tr>
        <#if (listSize > 0)>
          <#list productIds[lowIndex..highIndex-1] as productId>
              <@tr>
                <@td>[<#if productIdsCond.contains(productId)>x<#else>&nbsp;</#if>]</@td>
                <@td>[<#if productIdsAction.contains(productId)>x<#else>&nbsp;</#if>]</@td>
                <@td>
                  ${setRequestAttribute("optProductId", productId)}
                  ${setRequestAttribute("listIndex", productId_index)}
                  ${screens.render(productsummaryScreen)}
                </@td>
              </@tr>
          </#list>
        </#if>
        </@table>
    </@section>
</#if>
