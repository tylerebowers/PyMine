package com.tylerebowers.client;

import com.tylerebowers.client.api.ApiServer;
import net.fabricmc.api.ClientModInitializer;

public class PymineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Start the localhost control API (default port 8765, -Dpymine.port=NNNN to change).
		ApiServer.start();
	}
}
