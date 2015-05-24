package net.floodlightcontroller.odin.master;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.util.U16;

import java.util.Collections;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.util.MACAddress;

@JsonSerialize(using=OdinAgentSerializer.class)
class OdinAgent implements IOdinAgent {

	// Connect to control socket on OdinAgent
	private Socket odinAgentSocket = null;
	private PrintWriter outBuf;
	private BufferedReader inBuf;
	private IOFSwitch ofSwitch;
	private InetAddress ipAddress;
	private long lastHeard;

	private ConcurrentSkipListSet<OdinClient> clientList = new ConcurrentSkipListSet<OdinClient>();

	// OdinAgent Handler strings
	private static final String READ_HANDLER_TABLE = "table";
	private static final String READ_HANDLER_RXSTATS = "rxstats";
	private static final String READ_HANDLER_SPECTRAL_SCAN = "spectral_scan";
	private static final String WRITE_HANDLER_ADD_VAP = "add_vap";
	private static final String WRITE_HANDLER_SET_VAP = "set_vap";
	private static final String WRITE_HANDLER_REMOVE_VAP = "remove_vap";
	private static final String WRITE_HANDLER_SUBSCRIPTIONS = "subscriptions";
	private static final String WRITE_HANDLER_SEND_PROBE_RESPONSE = "send_probe_response";
	private static final String WRITE_HANDLER_SPECTRAL_SCAN = "spectral_scan";
	private static final String ODIN_AGENT_ELEMENT = "odinagent";

	private final int RX_STAT_NUM_PROPERTIES = 5;
	private final int ODIN_AGENT_PORT = 6777;

	
	/**
	 * Probably need a better identifier
	 * 
	 * @return the agent's IP address
	 */
	public InetAddress getIpAddress() {
		return ipAddress;
	}

	
	/**
	 * Returns timestamp of last heartbeat from agent
	 * 
	 * @return Timestamp
	 */
	public long getLastHeard() {
		return lastHeard;
	}

	
	/**
	 * Set the lastHeard timestamp of a client
	 * 
	 * @param t  timestamp to update lastHeard value
	 */
	public void setLastHeard(long t) {
		this.lastHeard = t;
	}

	
	/**
	 * Probe the agent for a list of VAPs its hosting. This should only be used
	 * by the master when an agent registration to shield against master
	 * failures. The assumption is that when this is invoked, the controller has
	 * never heard about the agent before.
	 * 
	 * @return a list of OdinClient entities on the agent
	 */
	public Set<OdinClient> getLvapsRemote() {
		ConcurrentSkipListSet<OdinClient> clients = new ConcurrentSkipListSet<OdinClient>();
		String handle = invokeReadHandler(READ_HANDLER_TABLE);

		if (handle == null) {
			return clients; // empty list
		}

		String tableList[] = handle.split("\n");

		for (String entry : tableList) {

			if (entry.equals(""))
				break;
			
			/* 
			 * Every entry looks like this:
			 * properties:  [0]       [1]         [2]         [3, 4, 5...]
			 *           <sta_mac> <ipv4addr> <lvap bssid> <lvap ssid list>
			 *
			 */
			String properties[] = entry.split(" ");
			OdinClient oc;
			Lvap lvap;
			try {
				// First, get the list of all the SSIDs
				ArrayList<String> ssidList = new ArrayList<String>();
				for (int i = 3; i < properties.length; i++) {
					ssidList.add (properties[i]);
				}
				lvap =  new Lvap (MACAddress.valueOf(properties[2]), ssidList);
				oc = new OdinClient(MACAddress.valueOf(properties[0]),
						InetAddress.getByName(properties[1]), lvap);
				lvap.setAgent(this);
				clients.add(oc);
				
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
		}

		clientList = clients;

		return clients;
	}

	
	/**
	 * Return a list of LVAPs that the master knows this agent is hosting.
	 * Between the time an agent has crashed and the master detecting the crash,
	 * this can return stale values.
	 * 
	 * @return a list of OdinClient entities on the agent
	 */
	public Set<OdinClient> getLvapsLocal() {
		return clientList;
	}

	
	/**
	 * Retrive Rx-stats from the OdinAgent.
	 * 
	 * @return A map of stations' MAC addresses to a map of properties and
	 *         values.
	 */
	public Map<MACAddress, Map<String, String>> getRxStats() {
		String stats = invokeReadHandler(READ_HANDLER_RXSTATS);
		
		Map<MACAddress, Map<String, String>> ret = new HashMap<MACAddress, Map<String, String>>();

		/*
		 * We basically get rows like this MAC_ADDR1 prop1:<value> prop2:<value>
		 * MAC_ADDR2 prop1:<value> prop2:<value>
		 */
		String arr[] = stats.split("\n");
		for (String elem : arr) {
			String row[] = elem.split(" ");

			if (row.length != RX_STAT_NUM_PROPERTIES + 1) {
				continue;
			}

			MACAddress eth = MACAddress.valueOf(row[0].toLowerCase());

			Map<String, String> innerMap = new HashMap<String, String>();

			for (int i = 1; i < RX_STAT_NUM_PROPERTIES + 1; i += 1) {
				innerMap.put(row[i].split(":")[0], row[i].split(":")[1]);
			}

			ret.put(eth, Collections.unmodifiableMap(innerMap));
		}

		return Collections.unmodifiableMap(ret);
	}

	
	/**
	 * To be called only once, initialises a connection to the OdinAgent's
	 * control socket. We let the connection persist so as to save on
	 * setup/tear-down messages with every invocation of an agent. This will
	 * also help speedup handoffs.
	 * 
	 * @param host Click based OdinAgent host
	 * @param port Click based OdinAgent's control socket port
	 * @return 0 on success, -1 otherwise
	 */
	public int init(InetAddress host) {
		OFFlowMod flow2 = new OFFlowMod();
		{
			OFMatch match = new OFMatch();
			match.fromString("dl_type=0x0800,nw_proto=17,tp_dst=68");
			
			OFActionOutput actionOutput = new OFActionOutput ();
			actionOutput.setPort(OFPort.OFPP_CONTROLLER.getValue());
			actionOutput.setLength((short) OFActionOutput.MINIMUM_LENGTH);
			
			List<OFAction> actionList = new ArrayList<OFAction>();
			actionList.add(actionOutput);
			
		
			flow2.setCookie(67);
			flow2.setPriority((short) 200);
            flow2.setBufferId((int) -1);
			flow2.setMatch(match);
			flow2.setIdleTimeout((short) 0);
			flow2.setActions(actionList);
	        flow2.setLength(U16.t(OFFlowMod.MINIMUM_LENGTH + OFActionOutput.MINIMUM_LENGTH));
		}
		
		try {
			ofSwitch.write(flow2, null);
            System.out.println("DHCP flow rule is installed to " + host.getHostAddress());
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		try {
			odinAgentSocket = new Socket(host.getHostAddress(), ODIN_AGENT_PORT);
			outBuf = new PrintWriter(odinAgentSocket.getOutputStream(), true);
			inBuf = new BufferedReader(new InputStreamReader(odinAgentSocket
					.getInputStream()));
			ipAddress = host;
		} catch (UnknownHostException e) {
			e.printStackTrace();
			return -1;
		} catch (IOException e) {
			e.printStackTrace();
			return -1;
		}

		return 0;
	}

	
	/**
	 * Get the IOFSwitch for this agent
	 * 
	 * @return ofSwitch
	 */
	public IOFSwitch getSwitch() {
		return ofSwitch;
	}

	
	/**
	 * Set the IOFSwitch entity corresponding to this agent
	 * 
	 * @param sw the IOFSwitch entity for this agent
	 */
	public void setSwitch(IOFSwitch sw) {
		ofSwitch = sw;
	}

	
	/**
	 * Remove a virtual access point from the AP corresponding to this agent
	 * 
	 * @param staHwAddr The STA's ethernet address
	 */
	public void removeClientLvap(OdinClient oc) {
		invokeWriteHandler(WRITE_HANDLER_REMOVE_VAP, oc.getMacAddress()
				.toString());
		clientList.remove(oc);
	}

	
	/**
	 * Add a virtual access point to the AP corresponding to this agent
	 * 
	 * @param oc OdinClient entity
	 */
	public void addClientLvap(OdinClient oc) {
		assert (oc.getLvap() != null);
		
		String ssidList = "";
		
		for (String ssid: oc.getLvap().getSsids()) {
			ssidList += " " + ssid;
		}
		
		invokeWriteHandler(WRITE_HANDLER_ADD_VAP, oc.getMacAddress().toString()
				+ " " + oc.getIpAddress().getHostAddress() + " "
				+ oc.getLvap().getBssid().toString() + ssidList);
		clientList.add(oc);
	}

	
	/**
	 * Update a virtual access point with possibly new IP, BSSID, or SSID
	 * 
	 * @param oc OdinClient entity
	 */
	public void updateClientLvap(OdinClient oc) {
		assert (oc.getLvap() != null);
		
		String ssidList = "";
		
		for (String ssid: oc.getLvap().getSsids()) {
			ssidList += " " + ssid;
		}
		
		invokeWriteHandler(WRITE_HANDLER_SET_VAP, oc.getMacAddress().toString()
				+ " " + oc.getIpAddress().getHostAddress() + " "
				+ oc.getLvap().getBssid().toString() + ssidList);
	}

	
	/**
	 * Set subscriptions
	 * 
	 * @param subscriptions
	 * @param t timestamp to update lastHeard value
	 */
	public void setSubscriptions(String subscriptionList) {
		invokeWriteHandler(WRITE_HANDLER_SUBSCRIPTIONS, subscriptionList);
	}

	
	/**
	 * Internal method to invoke a read handler on the OdinAgent
	 * 
	 * @param handlerName OdinAgent handler
	 * @return read-handler string
	 */
	private synchronized String invokeReadHandler(String handlerName) {
		outBuf.println("READ " + ODIN_AGENT_ELEMENT + "." + handlerName);

		String line = "";

		try {
			String data = null;

			while ((data = inBuf.readLine()).contains("DATA") == false) {
				// skip all the crap that the Click control
				// socket tells us
			}

			int numBytes = Integer.parseInt(data.split(" ")[1]);

			while (numBytes != 0) {
				numBytes--;
				char[] buf = new char[1];
				inBuf.read(buf);
				line = line + new String(buf);
			}

			return line;
		} catch (IOException e) {
			e.printStackTrace();
		}

		return null;
	}

	
	/**
	 * Internal method to invoke a write handler of the OdinAgent
	 * 
	 * @param handlerName OdinAgent write handler name
	 * @param handlerText Write string
	 */
	private synchronized void invokeWriteHandler(String handlerName,
			String handlerText) {
		outBuf.println("WRITE " + ODIN_AGENT_ELEMENT + "." + handlerName + " "
				+ handlerText);
	}


	@Override
	public void sendProbeResponse(MACAddress clientHwAddr, MACAddress bssid, Set<String> ssidList) {
		StringBuilder sb = new StringBuilder();
		sb.append(clientHwAddr);
		sb.append(" ");
		sb.append(bssid);
		
		for (String ssid: ssidList) {
			sb.append(" ");
			sb.append(ssid);
		}

		invokeWriteHandler(WRITE_HANDLER_SEND_PROBE_RESPONSE, sb.toString());
	}
}
