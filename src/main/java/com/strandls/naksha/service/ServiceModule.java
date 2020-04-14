package com.strandls.naksha.service;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.strandls.naksha.service.impl.GeoserverServiceImpl;
import com.strandls.naksha.service.impl.MetaLayerServiceImpl;

public class ServiceModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(MetaLayerService.class).to(MetaLayerServiceImpl.class).in(Scopes.SINGLETON);
		bind(GeoserverService.class).to(GeoserverServiceImpl.class).in(Scopes.SINGLETON);
	}
}