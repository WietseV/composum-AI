<%@page session="false" pageEncoding="UTF-8" %>
<%@taglib prefix="sling" uri="http://sling.apache.org/taglibs/sling/1.2" %>
<%@taglib prefix="cpn" uri="http://sling.composum.com/cpnl/1.0" %>
<%@taglib prefix="cpp" uri="http://sling.composum.com/cppl/1.0" %>
<%@taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<cpp:defineFrameObjects/>
<cpp:element var="model" type="com.composum.chatgpt.bundle.model.ChatGPTCategorizeDialogModel"
             cssBase="composum-chatgpt-pagesintegration-dialogs-categorize"
             id="chatgpt-categorize-dialog" cssAdd="dialog modal fade" role="dialog">
    <%--@elvariable id="model" type="com.composum.chatgpt.bundle.model.ChatGPTCategorizeDialogModel"--%>
    <div class="modal-dialog form-panel">
        <div class="modal-content">
            <form class="widget-form">
                <input name="_charset_" type="hidden" value="UTF-8"/>
                <input name="path" type="hidden" value="${model.path}"/>

                <div class="modal-header">
                    <button type="button" class="close fa fa-close" data-dismiss="modal" aria-label="Close"></button>
                    <cpn:text tagName="h4" class="modal-title dialog_title text"
                              i18n="true">Page Category Suggestions</cpn:text>
                </div>
                <div class="modal-body">
                    <div class="messages">
                        <div class="alert" style="display: none;"></div>
                    </div>

                    <div class="panel panel-default">
                        <div class="panel-heading" role="tab">
                            <cpn:text tagName="h4" class="panel-title dialog_title text"
                                      i18n="true">Current Categories</cpn:text>
                        </div>
                        <div class="panel-body">
                            <div class="current-categories">
                                <c:forEach var="category" items="${model.currentCategories}">
                                    <div class="category-item form-group">
                                        <div class="category-select">
                                            <label class="composum-pages-edit-widget_option">
                                                <input class="category-select-checkbox ${model.singleCategoryClass}"
                                                       type="checkbox" name="currentCategories"
                                                       value="${category.categoryKey}">
                                                <span class="label-text">${category.categoryName}:</span></label>
                                        </div>
                                    </div>
                                </c:forEach>
                            </div>
                        </div>
                    </div>

                    <div class="panel panel-default">
                        <div class="panel-heading" role="tab">
                            <cpn:text tagName="h4" class="panel-title dialog_title text"
                                      i18n="true">Suggested Categories</cpn:text>
                        </div>
                        <div class="panel-body suggestions">
                            <div class="loading-curtain" style="display: none;"><i
                                    class="fa fa-spinner fa-pulse fa-3x fa-fw"></i></div>
                            <div class="suggestion form-group" style="display: none;"></div>
                        </div>
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-dismiss="modal">Cancel</button>
                    <button type="button" class="btn btn-primary accept" data-dismiss="modal" disabled>Accept</button>
                </div>
            </form>
        </div>
    </div>
</cpp:element>
