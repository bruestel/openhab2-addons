/* globals Chart:false, feather:false */

(function () {
    'use strict'

    feather.replace();

    $(".redirectUri").text(window.location.href.substring(0, window.location.href.lastIndexOf('/homeconnect') + 12));

    $('#requestDetailModal').on('show.bs.modal', function (event) {
        var button = $(event.relatedTarget);
        var requestId = button.data('request-id');
        var request = requests.find(item=>item.id==requestId);
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

    $('.reload-page').click(function() {
        location.reload();
    });

    // // Graphs
    // var ctx = document.getElementById('myChart')
    // // eslint-disable-next-line no-unused-vars
    // var myChart = new Chart(ctx, {
    //     type: 'line',
    //     data: {
    //         labels: [
    //             'Sunday',
    //             'Monday',
    //             'Tuesday',
    //             'Wednesday',
    //             'Thursday',
    //             'Friday',
    //             'Saturday'
    //         ],
    //         datasets: [{
    //             data: [
    //                 15339,
    //                 21345,
    //                 18483,
    //                 24003,
    //                 23489,
    //                 24092,
    //                 12034
    //             ],
    //             lineTension: 0,
    //             backgroundColor: 'transparent',
    //             borderColor: '#007bff',
    //             borderWidth: 4,
    //             pointBackgroundColor: '#007bff'
    //         }]
    //     },
    //     options: {
    //         scales: {
    //             yAxes: [{
    //                 ticks: {
    //                     beginAtZero: false
    //                 }
    //             }]
    //         },
    //         legend: {
    //             display: false
    //         }
    //     }
    // })
}())
