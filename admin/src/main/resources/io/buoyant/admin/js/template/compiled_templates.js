define(['handlebars.runtime'], function(Handlebars) {
  Handlebars = Handlebars["default"];  var template = Handlebars.template, templates = Handlebars.templates = Handlebars.templates || {};
templates['barchart'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper;

  return "    <div class=\"bar-chart-label metric-header warning pull-left\">\n    "
    + container.escapeExpression(((helper = (helper = helpers.warningLabel || (depth0 != null ? depth0.warningLabel : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"warningLabel","hash":{},"data":data}) : helper)))
    + "\n    </div>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=container.lambda, alias2=container.escapeExpression, alias3=depth0 != null ? depth0 : {}, alias4=helpers.helperMissing, alias5="function";

  return "<div class=\"metrics-bar-chart-container\">\n  <div class=\"bar-chart-label metric-header pull-left\">\n    "
    + alias2(alias1(((stack1 = (depth0 != null ? depth0.label : depth0)) != null ? stack1.description : stack1), depth0))
    + ": "
    + alias2(alias1(((stack1 = (depth0 != null ? depth0.label : depth0)) != null ? stack1.value : stack1), depth0))
    + "\n  </div>\n\n"
    + ((stack1 = helpers["if"].call(alias3,(depth0 != null ? depth0.warningLabel : depth0),{"name":"if","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "\n  <div class=\"clearfix\"></div>\n  <div class=\"overlay-bars bar-container graph-gradient "
    + alias2(((helper = (helper = helpers.color || (depth0 != null ? depth0.color : depth0)) != null ? helper : alias4),(typeof helper === alias5 ? helper.call(alias3,{"name":"color","hash":{},"data":data}) : helper)))
    + "\" style=\"width:"
    + alias2(((helper = (helper = helpers.barContainerWidth || (depth0 != null ? depth0.barContainerWidth : depth0)) != null ? helper : alias4),(typeof helper === alias5 ? helper.call(alias3,{"name":"barContainerWidth","hash":{},"data":data}) : helper)))
    + "px;\"></div>\n  <div class=\"overlay-bars bar graph-gradient "
    + alias2(((helper = (helper = helpers.color || (depth0 != null ? depth0.color : depth0)) != null ? helper : alias4),(typeof helper === alias5 ? helper.call(alias3,{"name":"color","hash":{},"data":data}) : helper)))
    + "\" style=\"width:"
    + alias2(((helper = (helper = helpers.barWidth || (depth0 != null ? depth0.barWidth : depth0)) != null ? helper : alias4),(typeof helper === alias5 ? helper.call(alias3,{"name":"barWidth","hash":{},"data":data}) : helper)))
    + "px;\"></div>\n</div>\n";
},"useData":true});
templates['delegatenode'] = template({"1":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function";

  return "  <div class='panel panel-default'>\n    <div class='panel-heading\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.isPrimary : depth0),{"name":"if","hash":{},"fn":container.program(2, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "'"
    + ((stack1 = helpers["with"].call(alias1,(depth0 != null ? depth0.dentry : depth0),{"name":"with","hash":{},"fn":container.program(7, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + ">\n      <div class='node-info'>\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.isDelegate : depth0),{"name":"if","hash":{},"fn":container.program(9, data, 0),"inverse":container.program(11, data, 0),"data":data})) != null ? stack1 : "")
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.weight : depth0),{"name":"if","hash":{},"fn":container.program(23, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "      </div>\n      "
    + container.escapeExpression(((helper = (helper = helpers.path || (depth0 != null ? depth0.path : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"path","hash":{},"data":data}) : helper)))
    + "\n"
    + ((stack1 = helpers["with"].call(alias1,(depth0 != null ? depth0.dentry : depth0),{"name":"with","hash":{},"fn":container.program(25, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.transformation : depth0),{"name":"if","hash":{},"fn":container.program(27, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "    </div>\n    <ul class='list-group'>"
    + ((stack1 = ((helper = (helper = helpers.child || (depth0 != null ? depth0.child : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"child","hash":{},"data":data}) : helper))) != null ? stack1 : "")
    + "</ul>\n  </div>\n";
},"2":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isSuccess : depth0),{"name":"if","hash":{},"fn":container.program(3, data, 0),"inverse":container.program(5, data, 0),"data":data})) != null ? stack1 : "")
    + "    ";
},"3":function(container,depth0,helpers,partials,data) {
    return "        panel-heading-success\n";
},"5":function(container,depth0,helpers,partials,data) {
    return "        panel-heading-danger\n";
},"7":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "\n      data-dentry-prefix='"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + "' data-dentry-dst='"
    + alias4(((helper = (helper = helpers.dst || (depth0 != null ? depth0.dst : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dst","hash":{},"data":data}) : helper)))
    + "'\n    ";
},"9":function(container,depth0,helpers,partials,data) {
    return "          Possible Resolution Path\n";
},"11":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isAlt : depth0),{"name":"if","hash":{},"fn":container.program(12, data, 0),"inverse":container.program(14, data, 0),"data":data})) != null ? stack1 : "");
},"12":function(container,depth0,helpers,partials,data) {
    return "          Several Possible Resolution Paths\n";
},"14":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isUnion : depth0),{"name":"if","hash":{},"fn":container.program(15, data, 0),"inverse":container.program(17, data, 0),"data":data})) != null ? stack1 : "");
},"15":function(container,depth0,helpers,partials,data) {
    return "          Several Simultaneous Resolution Paths\n";
},"17":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isTransformation : depth0),{"name":"if","hash":{},"fn":container.program(18, data, 0),"inverse":container.program(20, data, 0),"data":data})) != null ? stack1 : "");
},"18":function(container,depth0,helpers,partials,data) {
    return "          Transformation\n";
},"20":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isLeaf : depth0),{"name":"if","hash":{},"fn":container.program(21, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "");
},"21":function(container,depth0,helpers,partials,data) {
    return "          Namer Match\n        ";
},"23":function(container,depth0,helpers,partials,data) {
    var helper;

  return "          (weight: "
    + container.escapeExpression(((helper = (helper = helpers.weight || (depth0 != null ? depth0.weight : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"weight","hash":{},"data":data}) : helper)))
    + ")\n";
},"25":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "        <span class='node-dentry'>"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + " => "
    + alias4(((helper = (helper = helpers.dst || (depth0 != null ? depth0.dst : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dst","hash":{},"data":data}) : helper)))
    + "</span>\n";
},"27":function(container,depth0,helpers,partials,data) {
    var helper;

  return "        <span class='node-dentry'>"
    + container.escapeExpression(((helper = (helper = helpers.transformation || (depth0 != null ? depth0.transformation : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"transformation","hash":{},"data":data}) : helper)))
    + "</span>\n";
},"29":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {};

  return "  <li class='list-group-item\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.isPrimary : depth0),{"name":"if","hash":{},"fn":container.program(30, data, 0),"inverse":container.program(35, data, 0),"data":data})) != null ? stack1 : "")
    + "'\n\n"
    + ((stack1 = helpers["with"].call(alias1,(depth0 != null ? depth0.dentry : depth0),{"name":"with","hash":{},"fn":container.program(37, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + ">\n\n    <div class='node-info'>\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.isNeg : depth0),{"name":"if","hash":{},"fn":container.program(39, data, 0),"inverse":container.program(41, data, 0),"data":data})) != null ? stack1 : "")
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.weight : depth0),{"name":"if","hash":{},"fn":container.program(52, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "    </div>\n\n"
    + ((stack1 = helpers["with"].call(alias1,(depth0 != null ? depth0.addr : depth0),{"name":"with","hash":{},"fn":container.program(54, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "\n    <span>\n      "
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.id : depth0),{"name":"if","hash":{},"fn":container.program(57, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "\n      <span class='path'>(residual: "
    + container.escapeExpression(((helper = (helper = helpers.path || (depth0 != null ? depth0.path : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(alias1,{"name":"path","hash":{},"data":data}) : helper)))
    + ")</span>\n    </span>\n\n"
    + ((stack1 = helpers["with"].call(alias1,(depth0 != null ? depth0.dentry : depth0),{"name":"with","hash":{},"fn":container.program(59, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "  </li>\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.transformed : depth0),{"name":"if","hash":{},"fn":container.program(61, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "");
},"30":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isSuccess : depth0),{"name":"if","hash":{},"fn":container.program(31, data, 0),"inverse":container.program(33, data, 0),"data":data})) != null ? stack1 : "");
},"31":function(container,depth0,helpers,partials,data) {
    return "        list-group-item-success\n";
},"33":function(container,depth0,helpers,partials,data) {
    return "        list-group-item-danger\n";
},"35":function(container,depth0,helpers,partials,data) {
    return "      list-group-item-plain\n    ";
},"37":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "      data-dentry-prefix='"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + "' data-dentry-dst='"
    + alias4(((helper = (helper = helpers.dst || (depth0 != null ? depth0.dst : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dst","hash":{},"data":data}) : helper)))
    + "'\n    ";
},"39":function(container,depth0,helpers,partials,data) {
    return "        No Further Branch Matches\n";
},"41":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isFail : depth0),{"name":"if","hash":{},"fn":container.program(42, data, 0),"inverse":container.program(44, data, 0),"data":data})) != null ? stack1 : "");
},"42":function(container,depth0,helpers,partials,data) {
    return "        Explicit Failure\n";
},"44":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isException : depth0),{"name":"if","hash":{},"fn":container.program(45, data, 0),"inverse":container.program(50, data, 0),"data":data})) != null ? stack1 : "");
},"45":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.message : depth0),{"name":"if","hash":{},"fn":container.program(46, data, 0),"inverse":container.program(48, data, 0),"data":data})) != null ? stack1 : "");
},"46":function(container,depth0,helpers,partials,data) {
    var helper;

  return "          Exception: "
    + container.escapeExpression(((helper = (helper = helpers.message || (depth0 != null ? depth0.message : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"message","hash":{},"data":data}) : helper)))
    + "\n";
},"48":function(container,depth0,helpers,partials,data) {
    return "          Unknown Exception\n";
},"50":function(container,depth0,helpers,partials,data) {
    return "        Bound Path\n      ";
},"52":function(container,depth0,helpers,partials,data) {
    var helper;

  return "        (weight: "
    + container.escapeExpression(((helper = (helper = helpers.weight || (depth0 != null ? depth0.weight : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"weight","hash":{},"data":data}) : helper)))
    + ")\n";
},"54":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.addrs : depth0),{"name":"each","hash":{},"fn":container.program(55, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "");
},"55":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "        "
    + alias4(((helper = (helper = helpers.ip || (depth0 != null ? depth0.ip : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"ip","hash":{},"data":data}) : helper)))
    + ":"
    + alias4(((helper = (helper = helpers.port || (depth0 != null ? depth0.port : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"port","hash":{},"data":data}) : helper)))
    + "\n";
},"57":function(container,depth0,helpers,partials,data) {
    var helper;

  return "["
    + container.escapeExpression(((helper = (helper = helpers.id || (depth0 != null ? depth0.id : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"id","hash":{},"data":data}) : helper)))
    + "]";
},"59":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "      <span class='node-dentry'>"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + " => "
    + alias4(((helper = (helper = helpers.dst || (depth0 != null ? depth0.dst : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dst","hash":{},"data":data}) : helper)))
    + "</span>\n";
},"61":function(container,depth0,helpers,partials,data) {
    var stack1, helper;

  return "  <ul class='list-group'>"
    + ((stack1 = ((helper = (helper = helpers.transformed || (depth0 != null ? depth0.transformed : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"transformed","hash":{},"data":data}) : helper))) != null ? stack1 : "")
    + "</ul>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.child : depth0),{"name":"if","hash":{},"fn":container.program(1, data, 0),"inverse":container.program(29, data, 0),"data":data})) != null ? stack1 : "");
},"useData":true});
templates['delegator'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    return "<div class=\"row\">\n  <div class=\"col-lg-6\">\n    <div class=\"input-group path\">\n      <span class=\"input-group-addon\" id=\"basic-addon1\">Path</span>\n      <input type=\"text\" class=\"form-control\" id=\"path-input\" placeholder=\"/path/to/resource\" aria-describedby=\"basic-addon1\">\n      <span class=\"input-group-btn\">\n        <button class=\"btn btn-default go\" type=\"button\">Go!</button>\n      </span>\n    </div>\n  </div>\n</div>\n\n<div class=\"dtab\">\n  <h4 class=\"header\">\n    Dtab\n  </h4>\n  <div id=\"dtab\"></div>\n  <div class=\"namerd-dtab-warning hide\">Note: the above Dentries are from namerd and can't be edited.</div>\n  <div id=\"dtab-base\"></div>\n\n  <div id=\"dtab-edit\" class=\"hide\">\n    <textarea type=\"text\" class=\"form-control\" id=\"dtab-input\" placeholder=\"\"></textarea>\n  </div>\n\n  <button id=\"edit-dtab-btn\" class=\"btn btn-info btn-sm\">Edit</button>\n  <button id=\"save-dtab-btn\" class=\"btn btn-success btn-sm hide disabled\">Apply*</button>\n  <a target=\"_blank\" id=\"reset-dtab-link\" class=\"hide\">Reset</a>\n  <div class=\"save-warning\">*Applies dtab for playground testing only!</div>\n</div>\n\n<div class=\"result\"></div>\n\n<div class=\"modal fade error-modal\" tabindex=\"-1\" role=\"dialog\"></div>\n\n<div class=\"modal fade confirm-modal\" tabindex=\"-1\" role=\"dialog\">\n  <div class=\"modal-dialog modal-sm\">\n    <div class=\"modal-content\">\n      <div class=\"modal-body\">Reset Dtab to initial state?</div>\n      <div class=\"modal-footer\">\n        <button type=\"button\" class=\"btn btn-secondary\" data-dismiss=\"modal\">Cancel</button>\n        <button type=\"button\" class=\"btn btn-warning confirm\" data-dismiss=\"modal\">Reset</button>\n      </div>\n    </div>\n  </div>\n</div>\n";
},"useData":true});
templates['dentry'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "<div class='dentry'>\n  <div class='dentry-part dentry-prefix' data-dentry-prefix='"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + "'>\n    <span class='prefix-content'>"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + "</span>\n  </div>\n  <div class='fake-column text-center'>=></div>\n  <div class='dentry-part dentry-dst' data-dentry-dst='"
    + alias4(((helper = (helper = helpers.dst || (depth0 != null ? depth0.dst : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dst","hash":{},"data":data}) : helper)))
    + "'>\n    <span class='dst-content'>"
    + alias4(((helper = (helper = helpers.dst || (depth0 != null ? depth0.dst : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dst","hash":{},"data":data}) : helper)))
    + "</span>\n  </div>\n  <div class='fake-column'>;</div>\n</div>\n<div class='clearfix'></div>\n";
},"useData":true});
templates['error_modal'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    return "<div class=\"modal-dialog modal-sm\">\n  <div class=\"modal-content\">\n    <div class=\"modal-header\">\n      <button type=\"button\" class=\"close\" data-dismiss=\"modal\" aria-label=\"Close\"><span aria-hidden=\"true\">&times;</span></button>\n      <h4 class=\"modal-title\">Whoops!</h4>\n    </div>\n    <div class=\"modal-body\">\n      <p>Looks like there was an issue completing your request.</p>\n      <pre>"
    + container.escapeExpression(container.lambda(depth0, depth0))
    + "</pre>\n    </div>\n  </div>\n</div>\n";
},"useData":true});
templates['latencies.partial'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "      <div>\n        <span class=\"latency-label\">\n          <span class=\"latency-legend\" style=\"background-color:"
    + alias4(((helper = (helper = helpers.latencyColor || (depth0 != null ? depth0.latencyColor : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"latencyColor","hash":{},"data":data}) : helper)))
    + ";\">&nbsp;</span>"
    + alias4(((helper = (helper = helpers.latencyLabel || (depth0 != null ? depth0.latencyLabel : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"latencyLabel","hash":{},"data":data}) : helper)))
    + "\n        </span>\n        <span class=\"pull-right latency-value\">"
    + alias4(((helper = (helper = helpers.latencyValue || (depth0 != null ? depth0.latencyValue : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"latencyValue","hash":{},"data":data}) : helper)))
    + " ms</span>\n      </div>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<div class=\"col-md-2 router-latencies-container\">\n  <div class=\"metric-header\">Latencies</div>\n  <div class=\"router-latencies\">\n"
    + ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},depth0,{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "  </div>\n</div>\n";
},"useData":true});
templates['logging_row'] = template({"1":function(container,depth0,helpers,partials,data) {
    var stack1, alias1=container.lambda, alias2=container.escapeExpression;

  return "        <a class=\"btn btn-sm\n"
    + ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.isActive : depth0),{"name":"if","hash":{},"fn":container.program(2, data, 0),"inverse":container.program(4, data, 0),"data":data})) != null ? stack1 : "")
    + "\n          data-level="
    + alias2(alias1((depth0 != null ? depth0.level : depth0), depth0))
    + " data-logger="
    + alias2(alias1(((stack1 = (data && data.root)) && stack1.logger), depth0))
    + " href=\"#\">\n          "
    + alias2(alias1((depth0 != null ? depth0.level : depth0), depth0))
    + "\n        </a>\n";
},"2":function(container,depth0,helpers,partials,data) {
    return "            btn-primary active disabled\" ";
},"4":function(container,depth0,helpers,partials,data) {
    return " btn-default\" ";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {};

  return "<tr>\n  <td><h5>"
    + container.escapeExpression(((helper = (helper = helpers.logger || (depth0 != null ? depth0.logger : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(alias1,{"name":"logger","hash":{},"data":data}) : helper)))
    + "</h5></td>\n  <td>\n    <div class=\"btn-group pull-right\" role=\"group\">\n"
    + ((stack1 = helpers.each.call(alias1,(depth0 != null ? depth0.logLevels : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "    </div>\n  </td>\n</tr>\n";
},"useData":true});
templates['metric.partial'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper;

  return "      "
    + container.escapeExpression(((helper = (helper = helpers.description || (depth0 != null ? depth0.description : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"description","hash":{},"data":data}) : helper)))
    + "\n";
},"3":function(container,depth0,helpers,partials,data) {
    return "      metric\n";
},"5":function(container,depth0,helpers,partials,data) {
    var helper;

  return "      "
    + container.escapeExpression(((helper = (helper = helpers.value || (depth0 != null ? depth0.value : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"value","hash":{},"data":data}) : helper)))
    + "\n";
},"7":function(container,depth0,helpers,partials,data) {
    return "      0\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "<div class=\""
    + alias4(((helper = (helper = helpers.containerClass || (depth0 != null ? depth0.containerClass : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"containerClass","hash":{},"data":data}) : helper)))
    + "\">\n  <div class=\"metric-header\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.description : depth0),{"name":"if","hash":{},"fn":container.program(1, data, 0),"inverse":container.program(3, data, 0),"data":data})) != null ? stack1 : "")
    + "  </div>\n  <div class=\""
    + alias4(((helper = (helper = helpers.metricClass || (depth0 != null ? depth0.metricClass : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"metricClass","hash":{},"data":data}) : helper)))
    + " "
    + alias4(((helper = (helper = helpers.style || (depth0 != null ? depth0.style : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"style","hash":{},"data":data}) : helper)))
    + "\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.value : depth0),{"name":"if","hash":{},"fn":container.program(5, data, 0),"inverse":container.program(7, data, 0),"data":data})) != null ? stack1 : "")
    + "  </div>\n</div>\n";
},"useData":true});
templates['namerd_namespace'] = template({"1":function(container,depth0,helpers,partials,data) {
    var alias1=container.lambda, alias2=container.escapeExpression;

  return "        <a class=\"router-list-item\" href=\"/delegator?router="
    + alias2(alias1(depth0, depth0))
    + "\">"
    + alias2(alias1(depth0, depth0))
    + "</a>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {};

  return "<div class='namespace-container container-fluid'>\n  <h3>"
    + container.escapeExpression(((helper = (helper = helpers.namespace || (depth0 != null ? depth0.namespace : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(alias1,{"name":"namespace","hash":{},"data":data}) : helper)))
    + "\n    <small>used by\n"
    + ((stack1 = helpers.each.call(alias1,(depth0 != null ? depth0.routers : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "    </small>\n  </h3>\n</div>\n";
},"useData":true});
templates['namerd_stats'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<h2>Namerd stats</h2>\n<div class=\"namerd-metrics-container\">\n"
    + ((stack1 = container.invokePartial(partials.metricPartial,(depth0 != null ? depth0.connections : depth0),{"name":"metricPartial","hash":{"metricClass":"metric-large","containerClass":"metric-container"},"data":data,"indent":"  ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + ((stack1 = container.invokePartial(partials.metricPartial,(depth0 != null ? depth0.bindcache : depth0),{"name":"metricPartial","hash":{"metricClass":"metric-large","containerClass":"metric-container"},"data":data,"indent":"  ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + ((stack1 = container.invokePartial(partials.metricPartial,(depth0 != null ? depth0.addrcache : depth0),{"name":"metricPartial","hash":{"metricClass":"metric-large","containerClass":"metric-container"},"data":data,"indent":"  ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "</div>\n";
},"usePartial":true,"useData":true});
templates['process_info'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "      <li class=\"col-md-2\" data-key=\""
    + alias4(((helper = (helper = helpers.dataKey || (depth0 != null ? depth0.dataKey : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"dataKey","hash":{},"data":data}) : helper)))
    + "\">\n        <strong class=\"stat-label\">"
    + alias4(((helper = (helper = helpers.description || (depth0 != null ? depth0.description : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"description","hash":{},"data":data}) : helper)))
    + "</strong>\n        <span id=\""
    + alias4(((helper = (helper = helpers.elemId || (depth0 != null ? depth0.elemId : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"elemId","hash":{},"data":data}) : helper)))
    + "\" class=\"stat\">"
    + alias4(((helper = (helper = helpers.value || (depth0 != null ? depth0.value : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"value","hash":{},"data":data}) : helper)))
    + "</span>\n      </li>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<div id=\"process-info\">\n  <ul class=\"list-inline topline-stats\">\n"
    + ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.stats : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "  </ul>\n</div>\n";
},"useData":true});
templates['rate_metric.partial'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper;

  return "        "
    + container.escapeExpression(((helper = (helper = helpers.value || (depth0 != null ? depth0.value : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"value","hash":{},"data":data}) : helper)))
    + "\n";
},"3":function(container,depth0,helpers,partials,data) {
    return "        0\n";
},"5":function(container,depth0,helpers,partials,data) {
    var helper;

  return "        "
    + container.escapeExpression(((helper = (helper = helpers.rate || (depth0 != null ? depth0.rate : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"rate","hash":{},"data":data}) : helper)))
    + "\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "<div class=\""
    + alias4(((helper = (helper = helpers.containerClass || (depth0 != null ? depth0.containerClass : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"containerClass","hash":{},"data":data}) : helper)))
    + "\">\n  <div class=\"metric-header\">\n    "
    + alias4(((helper = (helper = helpers.description || (depth0 != null ? depth0.description : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"description","hash":{},"data":data}) : helper)))
    + "\n  </div>\n  <div>\n    <span class=\""
    + alias4(((helper = (helper = helpers.metricClass || (depth0 != null ? depth0.metricClass : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"metricClass","hash":{},"data":data}) : helper)))
    + "\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.value : depth0),{"name":"if","hash":{},"fn":container.program(1, data, 0),"inverse":container.program(3, data, 0),"data":data})) != null ? stack1 : "")
    + "    </span>\n\n    <span class=\"metric-small\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.rate : depth0),{"name":"if","hash":{},"fn":container.program(5, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "    </span>\n  </div>\n</div>\n";
},"useData":true});
templates['request_stats'] = template({"1":function(container,depth0,helpers,partials,data) {
    var alias1=container.lambda, alias2=container.escapeExpression;

  return "  <dt>"
    + alias2(alias1(depth0, depth0))
    + "</dt>\n  <dd data-key=\""
    + alias2(alias1(depth0, depth0))
    + "\">...</dd>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.keys : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "");
},"useData":true});
templates['request_totals'] = template({"1":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {};

  return "    <div class=\"col-md-2\">\n      <div class=\"metric-header\">\n        "
    + container.escapeExpression(((helper = (helper = helpers.description || (depth0 != null ? depth0.description : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(alias1,{"name":"description","hash":{},"data":data}) : helper)))
    + "\n      </div>\n      <div class=\"request-total\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.value : depth0),{"name":"if","hash":{},"fn":container.program(2, data, 0),"inverse":container.program(4, data, 0),"data":data})) != null ? stack1 : "")
    + "      </div>\n    </div>\n";
},"2":function(container,depth0,helpers,partials,data) {
    var helper;

  return "          "
    + container.escapeExpression(((helper = (helper = helpers.value || (depth0 != null ? depth0.value : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"value","hash":{},"data":data}) : helper)))
    + "\n";
},"4":function(container,depth0,helpers,partials,data) {
    return "          0\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<div class=\"row\">\n"
    + ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.metrics : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "</div>\n";
},"useData":true});
templates['router_client'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<div class=\"client-metrics row\">\n  <div class=\"col-md-2\">\n"
    + ((stack1 = container.invokePartial(partials.rateMetricPartial,((stack1 = (depth0 != null ? depth0.data : depth0)) != null ? stack1.successRate : stack1),{"name":"rateMetricPartial","hash":{"metricClass":"metric-large success-metric","containerClass":"metric-container"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + ((stack1 = container.invokePartial(partials.metricPartial,((stack1 = (depth0 != null ? depth0.data : depth0)) != null ? stack1.requests : stack1),{"name":"metricPartial","hash":{"metricClass":"metric-large","containerClass":"metric-container"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "  </div>\n\n  <div class=\"col-md-2\">\n"
    + ((stack1 = container.invokePartial(partials.metricPartial,((stack1 = (depth0 != null ? depth0.data : depth0)) != null ? stack1.failures : stack1),{"name":"metricPartial","hash":{"metricClass":"failure-metric metric-large","containerClass":"metric-container"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + ((stack1 = container.invokePartial(partials.metricPartial,((stack1 = (depth0 != null ? depth0.data : depth0)) != null ? stack1.connections : stack1),{"name":"metricPartial","hash":{"metricClass":"metric-large","containerClass":"metric-container"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "  </div>\n\n"
    + ((stack1 = container.invokePartial(partials.latencyPartial,(depth0 != null ? depth0.latencies : depth0),{"name":"latencyPartial","data":data,"indent":"  ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "</div>\n";
},"usePartial":true,"useData":true});
templates['router_client_container'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "<div class=\"client-container clearfix\">\n  <div class=\"header-line\">&nbsp;</div>\n  <div class=\"router-header-large\">\n    <div class=\"client-id\">\n      <div class=\"pull-left transformer-prefix\" style=\"display: none\">"
    + alias4(((helper = (helper = helpers.prefix || (depth0 != null ? depth0.prefix : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"prefix","hash":{},"data":data}) : helper)))
    + "</div>\n      <div class=\"pull-left client-suffix is-first\">"
    + alias4(((helper = (helper = helpers.client || (depth0 != null ? depth0.client : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"client","hash":{},"data":data}) : helper)))
    + "</div>\n    </div>\n    <div class=\"client-toggle pull-right\">\n      <a class=\"client-expand\" target=\"blank\">expand</a>\n      <a class=\"client-collapse\" target=\"blank\">collapse</a>\n    </div>\n  </div>\n  <div class=\"client-content-container\">\n    <div class=\"metrics-container col-md-6\"></div>\n    <div class=\"chart-container col-md-6\">\n      <div class=\"router-graph-header\">Client success rate</div>\n      <div class=\"client-success-rate\"></div>\n    </div>\n    <div class=\"clearfix\"></div>\n    <div class=\"bar-chart-container row\">\n      <div class=\"col-md-6 lb-bar-chart\"></div>\n    </div>\n  </div>\n</div>\n";
},"useData":true});
templates['router_container'] = template({"1":function(container,depth0,helpers,partials,data) {
    var alias1=container.lambda, alias2=container.escapeExpression;

  return "  <div class=\"router router-"
    + alias2(alias1(depth0, depth0))
    + " row\" data-router=\""
    + alias2(alias1(depth0, depth0))
    + "\">\n    <div class=\"summary row\"></div>\n\n    <div class=\"combined-client-graph\">\n      <div class=\"router-graph-header\">Requests per client</div>\n      <canvas class=\"router-graph\" height=\"181\"></canvas>\n    </div>\n\n    <div class=\"router-stats row\">\n      <div class=\"retries-bar-chart col-md-6\"></div>\n      <div class=\"retries-stats col-md-6\"></div>\n      <div class=\"clearfix\"></div>\n    </div>\n\n    <div class=\"clients router-clients\">\n      <div class=\"pull-left router-subsection-title\">Clients</div>\n      <div class=\"pull-right client-toggle\">\n        <a target=\"blank\" class=\"expand-all\">expand all</a> &middot;\n        <a target=\"blank\" class=\"collapse-all\">collapse all</a>\n      </div>\n    </div>\n    <div class=\"servers router-servers row\">\n      <div class=\"router-subsection-title\">Servers</div>\n    </div>\n  </div>\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.routers : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "");
},"useData":true});
templates['router_option'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper;

  return container.escapeExpression(((helper = (helper = helpers.label || (depth0 != null ? depth0.label : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"label","hash":{},"data":data}) : helper)));
},"3":function(container,depth0,helpers,partials,data) {
    var helper;

  return container.escapeExpression(((helper = (helper = helpers.protocol || (depth0 != null ? depth0.protocol : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"protocol","hash":{},"data":data}) : helper)));
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<li><a href='#' class='router-menu-option'>"
    + ((stack1 = helpers["if"].call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.label : depth0),{"name":"if","hash":{},"fn":container.program(1, data, 0),"inverse":container.program(3, data, 0),"data":data})) != null ? stack1 : "")
    + "</a></li>\n";
},"useData":true});
templates['router_server'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<div class=\"client-metrics row\">\n  <div class=\"col-md-2\">\n"
    + ((stack1 = container.invokePartial(partials.rateMetricPartial,((stack1 = (depth0 != null ? depth0.metrics : depth0)) != null ? stack1.success : stack1),{"name":"rateMetricPartial","hash":{"metricClass":"metric-large success-metric","containerClass":"success-metric-container metric-container col-md-2"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + ((stack1 = container.invokePartial(partials.rateMetricPartial,((stack1 = (depth0 != null ? depth0.metrics : depth0)) != null ? stack1.requests : stack1),{"name":"rateMetricPartial","hash":{"metricClass":"metric-large","containerClass":"neutral-metric-container metric-container col-md-2"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "  </div>\n\n  <div class=\"col-md-2\">\n"
    + ((stack1 = container.invokePartial(partials.rateMetricPartial,((stack1 = (depth0 != null ? depth0.metrics : depth0)) != null ? stack1.failures : stack1),{"name":"rateMetricPartial","hash":{"metricClass":"metric-large failure-metric","containerClass":"failure-metric-container metric-container col-md-2"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + ((stack1 = container.invokePartial(partials.rateMetricPartial,((stack1 = (depth0 != null ? depth0.metrics : depth0)) != null ? stack1.connections : stack1),{"name":"rateMetricPartial","hash":{"metricClass":"metric-large","containerClass":"neutral-metric-container metric-container col-md-2"},"data":data,"indent":"    ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "  </div>\n\n"
    + ((stack1 = container.invokePartial(partials.latencyPartial,(depth0 != null ? depth0.latencies : depth0),{"name":"latencyPartial","data":data,"indent":"  ","helpers":helpers,"partials":partials,"decorators":container.decorators})) != null ? stack1 : "")
    + "</div>\n";
},"usePartial":true,"useData":true});
templates['router_server_container'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var helper;

  return "<div class=\"server-header router-header-large\">\n  "
    + container.escapeExpression(((helper = (helper = helpers.server || (depth0 != null ? depth0.server : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"server","hash":{},"data":data}) : helper)))
    + "\n</div>\n\n<div class=\"router-server clearfix\">\n  <div class=\"server-metrics metrics-container col-md-6\"></div>\n  <div class=\"server-success-chart col-md-6\">\n    <div class=\"router-graph-header\">Server success rate</div>\n  </div>\n</div>\n";
},"useData":true});
templates['router_service_container'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "<div class=\"svc-container\" data-service=\""
    + alias4(((helper = (helper = helpers.service || (depth0 != null ? depth0.service : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"service","hash":{},"data":data}) : helper)))
    + "\">\n  <div class=\"metric-large\">"
    + alias4(((helper = (helper = helpers.service || (depth0 != null ? depth0.service : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"service","hash":{},"data":data}) : helper)))
    + "</div>\n  <div class=\"svc-metrics\"></div>\n</div>\n";
},"useData":true});
templates['router_service_metrics'] = template({"1":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {};

  return "  <div class=\"client-metrics row\">\n    <div class=\"col-md-1 trail-in\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.requests : depth0),{"name":"if","hash":{},"fn":container.program(2, data, 0),"inverse":container.program(4, data, 0),"data":data})) != null ? stack1 : "")
    + "    </div>\n    <div class=\"col-md-2\">"
    + container.escapeExpression(((helper = (helper = helpers.client || (depth0 != null ? depth0.client : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(alias1,{"name":"client","hash":{},"data":data}) : helper)))
    + "</div>\n  </div>\n";
},"2":function(container,depth0,helpers,partials,data) {
    var helper;

  return "        "
    + container.escapeExpression(((helper = (helper = helpers.requests || (depth0 != null ? depth0.requests : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"requests","hash":{},"data":data}) : helper)))
    + "\n";
},"4":function(container,depth0,helpers,partials,data) {
    return "        0\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1;

  return "<div class=\"service-client-metrics row\">\n  <div class=\"service-subsection-header\">Requests per client</div>\n"
    + ((stack1 = helpers.each.call(depth0 != null ? depth0 : {},(depth0 != null ? depth0.clients : depth0),{"name":"each","hash":{},"fn":container.program(1, data, 0),"inverse":container.noop,"data":data})) != null ? stack1 : "")
    + "</div>\n";
},"useData":true});
templates['router_services_container'] = template({"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var helper, alias1=depth0 != null ? depth0 : {}, alias2=helpers.helperMissing, alias3="function", alias4=container.escapeExpression;

  return "<div class=\"router row\" data-router=\""
    + alias4(((helper = (helper = helpers.router || (depth0 != null ? depth0.router : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"router","hash":{},"data":data}) : helper)))
    + "\">\n  <div class=\"router-subsection-title\">Router</div>\n  <div class=\"metric-large\">"
    + alias4(((helper = (helper = helpers.router || (depth0 != null ? depth0.router : depth0)) != null ? helper : alias2),(typeof helper === alias3 ? helper.call(alias1,{"name":"router","hash":{},"data":data}) : helper)))
    + "</div>\n\n  <div class=\"router-subsection-title svc-subsection\">Services</div>\n</div>\n";
},"useData":true});
templates['router_summary'] = template({"1":function(container,depth0,helpers,partials,data) {
    var helper;

  return "        "
    + container.escapeExpression(((helper = (helper = helpers.router || (depth0 != null ? depth0.router : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"router","hash":{},"data":data}) : helper)))
    + "\n";
},"3":function(container,depth0,helpers,partials,data) {
    return "        inactive...\n";
},"5":function(container,depth0,helpers,partials,data) {
    var helper;

  return "            "
    + container.escapeExpression(((helper = (helper = helpers.load || (depth0 != null ? depth0.load : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"load","hash":{},"data":data}) : helper)))
    + "\n";
},"7":function(container,depth0,helpers,partials,data) {
    return "            0\n";
},"9":function(container,depth0,helpers,partials,data) {
    var helper;

  return "            "
    + container.escapeExpression(((helper = (helper = helpers.requests || (depth0 != null ? depth0.requests : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"requests","hash":{},"data":data}) : helper)))
    + "\n";
},"11":function(container,depth0,helpers,partials,data) {
    var helper;

  return "            "
    + container.escapeExpression(((helper = (helper = helpers.retries || (depth0 != null ? depth0.retries : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"retries","hash":{},"data":data}) : helper)))
    + "\n";
},"13":function(container,depth0,helpers,partials,data) {
    var helper;

  return "            "
    + container.escapeExpression(((helper = (helper = helpers.successRate || (depth0 != null ? depth0.successRate : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"successRate","hash":{},"data":data}) : helper)))
    + "\n";
},"15":function(container,depth0,helpers,partials,data) {
    return "            N/A\n";
},"17":function(container,depth0,helpers,partials,data) {
    var helper;

  return "            "
    + container.escapeExpression(((helper = (helper = helpers.failureRate || (depth0 != null ? depth0.failureRate : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(depth0 != null ? depth0 : {},{"name":"failureRate","hash":{},"data":data}) : helper)))
    + "\n";
},"compiler":[7,">= 4.0.0"],"main":function(container,depth0,helpers,partials,data) {
    var stack1, helper, alias1=depth0 != null ? depth0 : {};

  return "\n<div class=\"router-summary router-"
    + container.escapeExpression(((helper = (helper = helpers.router || (depth0 != null ? depth0.router : depth0)) != null ? helper : helpers.helperMissing),(typeof helper === "function" ? helper.call(alias1,{"name":"router","hash":{},"data":data}) : helper)))
    + " row\">\n  <div class=\"router-header col-md-4 metric-large\">\n    <div class=\"router-subsection-title\">Router</div>\n    <div>\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.router : depth0),{"name":"if","hash":{},"fn":container.program(1, data, 0),"inverse":container.program(3, data, 0),"data":data})) != null ? stack1 : "")
    + "    </div>\n  </div>\n\n  <div class=\"router-data\">\n    <div class=\"metric-large\">\n      <div class=\"router-summary-stat col-md-2\">\n        <div class=\"metric-header\">Pending</div>\n        <div data-key=\"load\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.load : depth0),{"name":"if","hash":{},"fn":container.program(5, data, 0),"inverse":container.program(7, data, 0),"data":data})) != null ? stack1 : "")
    + "        </div>\n      </div>\n      <div class=\"router-summary-stat col-md-2\">\n        <div class=\"metric-header\">Requests</div>\n        <div data-key=\"requests\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.requests : depth0),{"name":"if","hash":{},"fn":container.program(9, data, 0),"inverse":container.program(7, data, 0),"data":data})) != null ? stack1 : "")
    + "        </div>\n      </div>\n      <div class=\"router-summary-stat col-md-2\">\n        <div class=\"metric-header\">Retries</div>\n        <div data-key=\"retries\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.retries : depth0),{"name":"if","hash":{},"fn":container.program(11, data, 0),"inverse":container.program(7, data, 0),"data":data})) != null ? stack1 : "")
    + "        </div>\n      </div>\n      <div class=\"router-summary-stat col-md-2\">\n        <div class=\"metric-header\">Success rate</div>\n        <div data-key=\"success-rate\" class=\"success-metric\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.successRate : depth0),{"name":"if","hash":{},"fn":container.program(13, data, 0),"inverse":container.program(15, data, 0),"data":data})) != null ? stack1 : "")
    + "        </div>\n      </div>\n      <div class=\"router-summary-stat col-md-2\">\n        <div class=\"metric-header\">Fail rate</div>\n        <div data-key=\"fail-rate\" class=\"failure-metric\">\n"
    + ((stack1 = helpers["if"].call(alias1,(depth0 != null ? depth0.failureRate : depth0),{"name":"if","hash":{},"fn":container.program(17, data, 0),"inverse":container.program(15, data, 0),"data":data})) != null ? stack1 : "")
    + "        </div>\n      </div>\n    </div>\n  </div>\n</div>\n";
},"useData":true});
return templates;
});
