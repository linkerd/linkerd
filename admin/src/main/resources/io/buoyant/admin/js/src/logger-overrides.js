$(function() {
  $("nav#sidebar").remove();
  $("#toggle").remove();

  $("td>div.btn-group").addClass("pull-right");
  $("thead>tr>th:eq(1)").addClass("pull-right");

  var navbar = '<nav class="navbar navbar-inverse">' +
  '      <div class="navbar-container">' +
  '        <div class="navbar-header">' +
  '          <a class="navbar-brand-img" href="/">' +
  '            <img alt="Logo" src="/files/images/linkerd-horizontal-white-transbg-vectorized.svg">' +
  '          </a>' +
  '        </div>' +
  '        <div id="navbar" class="collapse navbar-collapse">' +
  '          <ul class="nav navbar-nav">' +
  '            <li><a href="/delegator">dtab</a></li>' +
  '            <li><a href="/admin/logging">logging</a></li>' +
  '            <li><a href="/help">help</a></li>' +
  '          </ul>' +
  '        </div>' +
  '      </div>' +
  '    </nav>"';

  $(navbar).prependTo($("body"));
});
