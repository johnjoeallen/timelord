package com.timelord.controller.common;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Makes an empty query-string value behave as "no filter" instead of a
 * binding error. Without this, an HTML &lt;select&gt; "All types" option
 * (value="") submitted against a UUID/enum @RequestParam throws a
 * conversion exception — this affects both the dashboard filter forms and
 * the equivalent REST query parameters (deviceId, eventType, severity, ...).
 */
@ControllerAdvice
public class EmptyRequestParamAdvice {

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(true));
    }
}
