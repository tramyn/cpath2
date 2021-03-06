"use strict";

var dsApp = angular.module('dsApp', ['ngRoute','xeditable']);

dsApp.service('MyFileUpload', ['$http', function ($http) {
    this.uploadFileToUrl = function(file, uploadUrl, ds){
        var fd = new FormData();
        fd.append('file', file);
        $http.post(uploadUrl, fd, {
            transformRequest: angular.identity, //data 'as is' (no tr. to json)
            headers: {'Content-Type': undefined} //multipart/form-data will be auto-detected
        })
        .success(function(){
        	alert('File uploaded!');
        	if(ds && !ds.uploaded) {ds.uploaded = true;}
        })
        .error(function(data, status){
        	console.log(status + ' - ' + data.responseText);
        });
    };
}]);

dsApp.service('MyPubmed', ['$http', function ($http) {
	
	var euroPmcUrlPrefix = "http://www.ebi.ac.uk/europepmc/webservices/rest/search/query=EXT_ID:";
	var euroPmcUrlSuffix = "&format=json&callback=JSON_CALLBACK";
	
	/* get the publication summary from the Europe PubMed web service 
	 * by PMID (ds.pubmedId), using JSONP; nicely format the citation data;
	 * assign the resulting string to a new field in the ds */
    this.updateCitation = function(ds){
        $http.jsonp(euroPmcUrlPrefix+ds.pubmedId+euroPmcUrlSuffix)
        	.success(function(data){
        		var res = data.resultList.result[0];
        		var cite = res.authorString + " " +  res.title
        			+ " " + res.journalTitle + ". " + res.pubYear 
        			+ ";" + res.journalVolume 
        			+ "(" + res.issue + "):" + res.pageInfo;
        		
//        		console.log(res.pmid + ": " + res.title);      		
        		ds.citation = cite;
        	})
        	.error(function(data, status){
        		console.log(status + ' - ' + data.responseText);
        	});
    };
}]);

dsApp.directive('fileModel', ['$parse', function ($parse) {
    return {
        restrict: 'A',
        link: function(scope, element, attrs) {
            var model = $parse(attrs.fileModel);
            var modelSetter = model.assign;            
            element.bind('change', function(){
                scope.$apply(function(){
                    modelSetter(scope, element[0].files[0]);
                });
            });
        }
    };
}]);

//helps check a new datasource ID is unique while user's typing in the input field
dsApp.directive('didUnique', ['$filter', function ($filter) {
	return {
	    require: 'ngModel',
	    link: function (scope, elem, attrs, ctrl) {
	      elem.on('blur', function (evt) {
	        scope.$apply(function () {
        		var id = elem.val();
       			//filter returns a new 'exists' array (looking for lower-case, exact match)
       			var exists = $filter('filter')(scope.datasources, {identifier: id.toLowerCase()}, true);
       			if(exists.length) {		
       				ctrl.$setValidity('didunique', false);
       			} else {
       				ctrl.$setValidity('didunique', true);
       			}
	        });
	      });
	    }
	};
}]);

dsApp.run(function(editableOptions, editableThemes) {
	  // bootstrap3 theme (can be also 'bs2', 'default')
	  editableOptions.theme = 'bs3'; 
//	  editableThemes.bs3.inputClass = 'input-sm';
//	  editableThemes.bs3.buttonsClass = 'btn-sm';
});

dsApp.controller('DatasourcesController', function($scope, $http, $filter, MyFileUpload, MyPubmed) {
// data for a quick off-line test	
//	$scope.datasources = [
//	  {"identifier" : "pid", "iconUrl" : "http://pathway-commons.googlecode.com/files/nci_nature.png", "description" : "NCI_Nature"},
//	  {"identifier" : "psp", "iconUrl" : "http://pathway-commons.googlecode.com/files/psp.png", "description" : "PhosphoSite"},
//	  {"identifier" : "chebi", "iconUrl" : "http://pathway-commons.googlecode.com/files/chebi.png", "description" : "ChEBI SDF"},
//	];	
	
	$http.get('metadata/datasources').success(function(datasources) {
		
		$scope.datasources = datasources;
		
		for(var i=0; i<datasources.length; i++) {
			//get citation by PMID (ds.pubmedId) using PubMed web service:
			MyPubmed.updateCitation(datasources[i]);
		}
		
	});	
	
//	$http.get('log/totalok').success(function(tot) {
//		$scope.totalok = tot;
//	});
//	
//	$http.get('log/totalip').success(function(tot) {
//		$scope.totalip = tot;
//	});
	
	
	//cPath2 Metadata types and license options
	$scope.dtypes = [
	                   {value: 'WAREHOUSE'},
	                   {value: 'BIOPAX'},
	                   {value: 'PSI_MI'},
	                   {value: 'PSI_MITAB'},
	                   {value: 'MAPPING'}
	                  ];
	
	$scope.dlicenses = [
	                   {value: 'free'},
	                   {value: 'academic'},
	                   {value: 'purchase'}
	                  ];	
	
	$scope.showType = function(ds) {
	    var selected = $filter('filter')($scope.dtypes, {value: ds.type});
	    return (ds.type && selected.length) ? selected[0].value : 'Null';
	};
	
	$scope.showAvailability = function(ds) {
	    var selected = $filter('filter')($scope.dlicenses, {value: ds.availability});
	    return (ds.availability && selected.length) ? selected[0].value : 'Null';
	};	
	
	$scope.newDatasource = function() {		
		if($scope.fds.$valid) {
			var id = $scope.newIdentifier.toLowerCase(); //important!
			//filter returns a new array (looking for lower-case, exact matches)
			var exists = $filter('filter')($scope.datasources, {identifier: id}, true); 
			if(exists.length) {		
				alert(id + " already exists");
			} else {			
				var newds = {identifier: id, name:[id], content: []};
				$scope.datasources.unshift(newds); //add to the list's top
			}
		}
	};
		
	$scope.deleteDatasource = function(i) {
		//remove the datasource from the scope (datasources)
		var arr = $scope.datasources.splice(i,1);
		var ds = arr[0];
		$http({method: 'DELETE', url: 'admin/datasources/'+ds.identifier})
			.error(function(data, status) {
				console.log(status + ' - ' + data.responseText);
		});
	};
			
	$scope.saveDatasource = function(ds) {
		var id = ds.identifier.toLowerCase();
		
		//build a new object to POST/PUT to the server db
		//(trying to send 'ds' directly fails with 400, perhaps,
		// due to it has additional fields generated by Angular model...)
		var obj = new Object(); 
		obj.identifier = id;
		obj.availability= ds.availability;
		obj.cleanerClassname= ds.cleanerClassname;
		obj.converterClassname= ds.converterClassname;
		obj.description= ds.description;
		obj.iconUrl= ds.iconUrl;
		obj.name= ds.name;
		obj.pubmedId= ds.pubmedId;
		obj.type= ds.type;
		obj.urlToData= ds.urlToData;
		obj.urlToHomepage= ds.urlToHomepage;
		obj.content=[];
		console.log('Saving: ' + JSON.stringify(obj));
		
		// get datasource identifiers from the server to 
		// to decide whether add, delete, or update
		$http.get('metadata/datasources').success(function(existing) {	
			//looking for lower-case, exact id match
			var exists = $filter('filter')(existing, {identifier: id}, true);
			if(exists.length) {//old found, update
				$http.post('admin/datasources', obj)
					.error(function(data, status) {
						console.log(status + ' - ' + data);
				});
				
			} else { // create new
				$http.put('admin/datasources', obj)
					.error(function(data, status) {
						console.log(status + ' - ' + data);
				});
			}
		});
	};
	
	$scope.myFile = {}; //hash: datasource index -> file to be uploaded 
	
	$scope.uploadDatafile = function(ds) {
		var id = ds.identifier;
		var url = 'admin/datasources/'+id +'/file';
		var file = $scope.myFile[id];
		if(file) {
			MyFileUpload.uploadFileToUrl(file, url, ds);
			delete $scope.myFile[id];
		} else {
			alert('No file selected, datasource: ' + id);
		}
	};
		
	//makes a unique set of lower case strings
	$scope.uniqueStrings = function(strings) {
		var i, len=strings.length, out=[], h={};
		for (i=0;i<len;i++) {
			h[strings[i].toLowerCase()]=0;
		}
		for (i in h) {
			out.push(i);
		}
		return out;
	};
	
});
