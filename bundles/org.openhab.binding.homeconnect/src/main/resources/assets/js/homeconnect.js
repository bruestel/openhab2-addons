/* globals Chart:false, feather:false */

(function () {
    'use strict'

    feather.replace();

    $(".redirectUri").text(window.location.href.substring(0, window.location.href.lastIndexOf('/homeconnect') + 12));

    $('#apiDetailModal').on('show.bs.modal', function (event) {
        var button = $(event.relatedTarget);
        var thingId = button.data('thing-id');
        var action = button.data('api-action');
        var titleText = button.data('title');
        var modal = $(this);
        var title = modal.find('.modal-title');
        var subTitle = modal.find('.modal-subtitle');
        var responseBodyElement = modal.find('.modal-response-body');

        responseBodyElement.text('Loading...');
        title.text(titleText);
        subTitle.text(thingId);
        modal.modal('handleUpdate');

        let jqxhr = $.get( 'appliances?thingId=' + thingId + '&action=' + action, function(data) {
            responseBodyElement.text(JSON.stringify(data,null,'\t'));
        });
        jqxhr.fail(function(data) {
            responseBodyElement.text(JSON.stringify(data,null,'\t'));
        })
        jqxhr.always(function() {
            modal.modal('handleUpdate');
        });
    })

    $('#requestDetailModal').on('show.bs.modal', function (event) {
        var button = $(event.relatedTarget);
        var requestId = button.data('request-id');
        var request = requests.find(item => item.id == requestId);
        var requestHeader = request.homeConnectRequest.header;
        var requestBody = request.homeConnectRequest.body;
        var modal = $(this);
        var requestBodyElement = modal.find('.modal-request-body');
        var title = modal.find('.modal-title');
        var responseBodyElement = modal.find('.modal-response-body');
        var requestHeaderElement = modal.find('.modal-request-header');
        var responseHeaderElement = modal.find('.modal-response-header');

        title.text(request.homeConnectRequest.method + ' ' + request.homeConnectRequest.url);

        if (requestBody) {
            requestBodyElement.text(requestBody);
            requestBodyElement.removeClass('text-muted')
        } else {
            requestBodyElement.text('Empty request body');
            requestBodyElement.addClass('text-muted')
        }

        if (request.homeConnectResponse && request.homeConnectResponse.body) {
            responseBodyElement.text(request.homeConnectResponse.body);
            responseBodyElement.removeClass('text-muted')
        } else {
            responseBodyElement.text('Empty response body');
            responseBodyElement.addClass('text-muted')
        }

        responseHeaderElement.empty();
        if (request.homeConnectResponse && request.homeConnectResponse.header) {
            var responseHeader = request.homeConnectResponse.header;
            Object.keys(responseHeader).forEach(key => {
                console.log(`key=${key}  value=${responseHeader[key]}`);
                responseHeaderElement.append($(`<dt class="col-sm-4">${key}</dt>`));
                responseHeaderElement.append($(`<dd class="col-sm-8 text-break">${responseHeader[key]}</dd>`));
                responseHeaderElement.append($('<div class="w-100"></div>'));
            });
        }

        requestHeaderElement.empty();
        Object.keys(requestHeader).forEach(key => {
            console.log(`key=${key}  value=${requestHeader[key]}`);
            requestHeaderElement.append($(`<dt class="col-sm-4">${key}</dt>`));
            requestHeaderElement.append($(`<dd class="col-sm-8 text-break">${requestHeader[key]}</dd>`));
            requestHeaderElement.append($('<div class="w-100"></div>'));
        });

        modal.modal('handleUpdate');
    })

    $('.reload-page').click(function () {
        location.reload();
    });

    $('.request-chart').each(function(index, element) {
        var bridgeId = $(this).data('bridge-id');
        var chartElement = element;

        function makeplot(bridgeId, chartElement) {
            Plotly.d3.csv('homeconnect/bridges?bridgeId=' + bridgeId + '&action=request-csv', function (data) {
                processData(data, chartElement)
            });
        }

        function processData(allRows, chartElement) {
            console.log(allRows);
            var x = [], y = [], standardDeviation = [];

            for (var i = 0; i < allRows.length; i++) {
                var row = allRows[i];
                x.push( row['time'] );
                y.push( row['requests'] );
            }
            console.log( 'X',x, 'Y',y, 'SD',standardDeviation );
            makePlotly( x, y, standardDeviation, chartElement );
        }

        function makePlotly( x, y, standard_deviation, chartElement ){
            var traces = [{
                x: x,
                y: y,
                type: 'histogram',
                histfunc: 'sum',
                xbins: {
                    size: 1000
                }
            }];

            Plotly.newPlot(chartElement, traces,
                           {
                               xaxis: {
                                   rangemode: 'nonnegative',
                                   autorange: true,
                                   title: '',
                                   type: 'date'
                               },
                               yaxis: {
                                   title: 'Requests'
                               }
                           },
                           {
                               displayModeBar: false,
                               responsive: true
                           });
        };

        makeplot(bridgeId, chartElement);
    });
}())
