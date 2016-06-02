package net.quvox.jforexgw4py;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import py4j.GatewayServer;

import java.io.*;
import java.lang.reflect.Method;
import java.awt.image.BufferedImage;
import java.net.Socket;
import java.net.ServerSocket;

//import com.dukascopy.api.system.*;
import com.dukascopy.api.system.ISystemListener;
import com.dukascopy.api.system.IClient;
import com.dukascopy.api.system.ClientFactory;
import com.dukascopy.api.*;
import com.dukascopy.api.IEngine.*;


/**
 * 参考
 *  https://www.dukascopy.com/wiki/#IClient_functionality
 */

public class JForexGateway implements IStrategy {
    private static final Logger log = LoggerFactory.getLogger(JForexGateway.class);
    private static final double DEFAULT_SLIPPAGE = 5;
    
	private IClient client;
	private IContext context;
	//private IConsole console;
	private IEngine engine;
	private IAccount account;
	private IHistory history;
	private IDataService dataservice;
	private long strategyID = 0;

	private boolean is_running = false;
	private boolean flag_get_tick = false;
	private boolean flag_get_bar = false;

    private static boolean usePIN = false;
    private static int localport = 54345;

	private String jnlpUrl = Params.jnlpDemoUrl;

	// command - function mapping
	private Map<String, Method> cmd_mappings = new HashMap<String, Method>();
	private void setup_cmd_method_mapping() throws Exception{
		/*
		Method [] ms = this.getClass().getDeclaredMethods();
		for (Method m: ms) {
			int i = m.getParameterCount();
			if (i > 0)log.info(m.getName()+":"+m.getParameterTypes()[i-1].toString());
		}
		*/
		cmd_mappings.put("start", this.getClass().getMethod("startup", HashMap.class));
		cmd_mappings.put("stop", this.getClass().getMethod("stopStrategy", HashMap.class));
		cmd_mappings.put("marketorder", this.getClass().getMethod("doOrder", HashMap.class));
		cmd_mappings.put("limitorder", this.getClass().getMethod("doOrder", HashMap.class));
		cmd_mappings.put("stoporder", this.getClass().getMethod("doOrder", HashMap.class));
		cmd_mappings.put("mitorder", this.getClass().getMethod("doOrder", HashMap.class));
		cmd_mappings.put("modifyorder", this.getClass().getMethod("modifyOrder", HashMap.class));
		cmd_mappings.put("close", this.getClass().getMethod("closeOrder", HashMap.class));
		cmd_mappings.put("getorders", this.getClass().getMethod("getOrders", HashMap.class));
		cmd_mappings.put("tick", this.getClass().getMethod("getTick", HashMap.class));
		cmd_mappings.put("isonline", this.getClass().getMethod("checkOnline", HashMap.class));
		cmd_mappings.put("set", this.getClass().getMethod("setConfigurations", HashMap.class));
	}

	private Map<String, Integer> order_types = new HashMap<String, Integer>() {
		private static final long serialVersionUID = 1L;
		{
			put("marketorder", OrderTask.TYPE_MARKET_ORDER);
			put("limitorder", OrderTask.TYPE_LIMIT_ORDER);
			put("stoporder", OrderTask.TYPE_STOP_ORDER);
			put("mitorder", OrderTask.TYPE_LIMIT_ORDER);
		}
	};

    // for py4j
	PythonListener plistener;
	private Map<String, String>getHashMap(final String type) {
		return new HashMap<String, String>() {
			private static final long serialVersionUID = 1L;
			{
				put("type", type);
			}
		};
	}
	
	/**
	 * various events
	 */
	@Override
	public void onAccount(IAccount account) throws JFException {
		double profitLoss = 0;
		double totalAmount = 0;
		log.info("onAccount()");
		for (IOrder order : engine.getOrders()) {
			if(order.getState() == IOrder.State.FILLED){
				profitLoss += order.getProfitLossInUSD(); // XXXX in jpy
				totalAmount += order.getAmount();
			}
		}
		//account.getEquity() gets updated every 5 seconds 
		//whereas history.getEquity() gets calculated according to the last tick prices
		Map<String, String> returnInfo = getHashMap("on_account");
		returnInfo.put("type", "on_account");
		returnInfo.put("equity", String.valueOf(history.getEquity()));
		returnInfo.put("pl", String.valueOf(profitLoss));
		returnInfo.put("creditline", String.valueOf(account.getCreditLine()));
		returnInfo.put("balance", String.valueOf(account.getBalance()));
		returnInfo.put("amount", String.valueOf(totalAmount));
		notifyToPython(returnInfo);
	}
	
	@Override
	public void onStart(IContext ctx) throws JFException {
		log.info("onStart()");
		//console = context.getConsole();
		context = ctx;
		engine = context.getEngine();
		account = context.getAccount();
		history = context.getHistory();
		dataservice = context.getDataService();

		//subscribe to the instruments
		subscribeInstruments(Params.instruments);
		
		long currentTime = System.currentTimeMillis() / 1000L;
		String online = isOnline(currentTime) ? "true" : "false";

		Map<String, String> returnInfo = getHashMap("on_start");
		returnInfo.put("type", "on_start");
		returnInfo.put("user", account.getUserName());
		returnInfo.put("account_type", String.valueOf(engine.getType()));
		returnInfo.put("mode", String.valueOf(engine.getRunMode()));
		returnInfo.put("online", online);
		notifyToPython(returnInfo);
	}

	@Override
	public void onStop() throws JFException {
		log.info("onStop()");
		Map<String, String> returnInfo = getHashMap("on_stop");
		notifyToPython(returnInfo);
	}

	@Override
	public void onMessage(IMessage message) throws JFException {
		log.info("onMessage()");
		Map<String, String> returnInfo = getHashMap("on_message");
		IMessage.Type msg_type = message.getType();
		returnInfo.put("msg_type", msg_type.toString());
		if (message.getContent() != null) returnInfo.put("content", message.getContent());
		if (message.getReasons().size() > 0) returnInfo.put("reason", message.getReasons().toString());
		if (msg_type == IMessage.Type.INSTRUMENT_STATUS) {
			IInstrumentStatusMessage imsg = (IInstrumentStatusMessage)message;
			returnInfo.put("instrument", imsg.getInstrument().toString());
			returnInfo.put("tradable", String.valueOf(imsg.isTradable()));
		} else if (msg_type == IMessage.Type.NOTIFICATION) {
			returnInfo.put("content", message.getContent());
		} else if (msg_type == IMessage.Type.ORDER_CHANGED_OK || msg_type == IMessage.Type.ORDER_CHANGED_REJECTED ||
				   msg_type == IMessage.Type.ORDER_CLOSE_OK || msg_type == IMessage.Type.ORDER_CLOSE_REJECTED ||
				   msg_type == IMessage.Type.ORDER_FILL_OK || msg_type == IMessage.Type.ORDER_FILL_REJECTED ||
				   msg_type == IMessage.Type.ORDER_SUBMIT_OK || msg_type == IMessage.Type.ORDER_SUBMIT_REJECTED ||
				   msg_type == IMessage.Type.ORDERS_MERGE_OK || msg_type == IMessage.Type.ORDERS_MERGE_REJECTED ||
				   msg_type == IMessage.Type.STOP_LOSS_LEVEL_CHANGED || msg_type == IMessage.Type.WITHDRAWAL) {
			IOrder order = message.getOrder();
			returnInfo.put("order_status", msg_type.toString());
			returnInfo.put("label", order.getLabel());
			returnInfo.put("id", order.getId());
			returnInfo.put("instrument", order.getInstrument().toString().replaceAll("/", "_"));
			returnInfo.put("state", order.getState().name());
			String side = order.isLong() ? "long" : "short";
			returnInfo.put("side", side);
		} else if (msg_type == IMessage.Type.STOP_LOSS_LEVEL_CHANGED) {
			IStopLossLevelChangedMessage isllcm = (IStopLossLevelChangedMessage)message;
			returnInfo.put("prev_sl", String.valueOf(isllcm.previousStopLossLevel()));
			returnInfo.put("new_sl", String.valueOf(isllcm.stopLossLevel()));
		} else {
			returnInfo.put("alltext", message.toString());
		}
		if (message.getOrder() != null) returnInfo.put("order", message.getOrder().toString());
		notifyToPython(returnInfo);
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		log.info("onTick()");
		if (! flag_get_tick) return;
		Map<String, String> returnInfo = getHashMap("on_tick");
		returnInfo.put("instrument", instrument.toString().replaceAll("/", "_"));
		returnInfo.put("ask", String.valueOf(tick.getAsk()));
		returnInfo.put("bid", String.valueOf(tick.getBid()));
		returnInfo.put("ask_volume", String.valueOf(tick.getAskVolume()));
		returnInfo.put("bid_volume", String.valueOf(tick.getBidVolume()));
		returnInfo.put("time", String.valueOf(tick.getTime()));
		notifyToPython(returnInfo);
	}

	@Override
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		log.info("onBar()");
		if (! flag_get_bar) return;
		Map<String, String> returnInfo = getHashMap("on_bar");
		returnInfo.put("instrument", instrument.toString());
		returnInfo.put("period", period.toString());
		returnInfo.put("ask", askBar.toString());
		returnInfo.put("bid", bidBar.toString());
		returnInfo.put("ask_volume", String.valueOf(askBar.getVolume()));
		returnInfo.put("bid_volume", String.valueOf(bidBar.getVolume()));
		notifyToPython(returnInfo);
    }

	private boolean isOnline(long time) throws JFException{
	    Set<ITimeDomain> offlines = dataservice.getOfflineTimeDomains(time - Period.WEEKLY.getInterval(), time + Period.WEEKLY.getInterval());
	    for(ITimeDomain offline : offlines){
	        if( time > offline.getStart() &&  time < offline.getEnd()){
	            return true;
	        }
	    }
	    return false;
	}
	/**
	 * login process
	 */
	private void login() throws Exception {
		log.info("login()");
		client.setSystemListener(new ISystemListener() {
				private int reconnectCounter = 3;

                @Override
                public void onStart(long processId) {
					log.info("Strategy started: " + processId);
					notifyLog("Strategy started");
                }

				@Override
				public void onStop(long processId) {
					log.info("Strategy stopped: " + processId);
					notifyLog("Strategy stopped");
					if (client.getStartedStrategies().size() == 0) {
						System.exit(0);
					}
				}

				@Override
				public void onConnect() {
					log.info("Connected");
					notifyLog("Connected to dukascopy");
					reconnectCounter = 3;
				}
				
				@Override
				public void onDisconnect() {
					log.warn("Disconnected");
					notifyLog("Disconnected from dukascopy");
					if (reconnectCounter > 0) {
						client.reconnect();
						--reconnectCounter;
					} else {
						try {
							//sleep for 10 seconds before attempting to reconnect
							Thread.sleep(10000);
						} catch (InterruptedException e) {
							//ignore
						}
						try {
							client.connect(jnlpUrl, Params.userName, Params.password);
						} catch (Exception e) {
							log.error(e.getMessage(), e);
							System.exit(1);
						}
					}
				}
			});
		
		if (usePIN == false) {
			jnlpUrl = Params.jnlpDemoUrl;
			notifyLog("Connecting without pin to "+jnlpUrl);
			client.connect(jnlpUrl, Params.userName, Params.password);
		} else {
			jnlpUrl = Params.jnlpLiveUrl;
			notifyLog("Connecting without pin to "+jnlpUrl);
			BufferedImage img = client.getCaptchaImage(jnlpUrl); // pinコード入力のためのCAPCHAを取得する。
			
			ServerSocket srv = new ServerSocket(localport);      // pinコード表示ウインドウとソケット通信する。
			new ShowPinCode(img, localport);
			Socket sock = srv.accept();
			int len;
			byte[] buffer = new byte[1024];
			StringBuffer sb = new StringBuffer();
			DataInputStream input = new DataInputStream(sock.getInputStream());
			while (0 <= (len = input.read(buffer))) {
				sb.append(new String(buffer, 0, len));
			}
			Params.pin_code = sb.toString();
			log.info("connect using ["+Params.pin_code+"]");
			input.close();
			sock.close();
			srv.close();
			client.connect(jnlpUrl, Params.userName, Params.password, Params.pin_code);
        }

		//wait for it to connect
		int i = 10; //wait max ten seconds
		while (i > 0 && !client.isConnected()) {
			Thread.sleep(1000);
			i--;
        }
		if (!client.isConnected()) {
            log.error("Failed to connect Dukascopy servers");
            notifyFatal("Failed to connenct Dukascopy servers");
            System.exit(1);
        }
		notifyLog("logged_in");
	}

	/**
	 * operation commands
	 */
	public void startup(HashMap<String,String> hm) {
		log.info("startup()");
		if ( is_running ) return;
		if (hm.containsKey("user")) Params.userName = hm.get("user");
		if (hm.containsKey("pass")) Params.password = hm.get("pass");
		if (hm.containsKey("instruments")) Params.instruments = hm.get("instruments").replaceAll("_", "/");
		if (hm.containsKey("live")) usePIN = true;

		try {
			client = ClientFactory.getDefaultInstance();
			login();
		} catch (Exception e) {
			log.info(e.toString());
			notifyFatal(e.toString());
			System.exit(1);
		}

		//workaround for LoadNumberOfCandlesAction for JForex-API versions > 2.6.64
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			log.info(e.toString());
		}

		//start the strategy
		log.info("Starting strategy");
		notifyLog("Starting strategy");
		try {
			strategyID = client.startStrategy(this);
		} catch (Exception e) {
			log.info(e.toString());
			notifyFatal(e.toString());
			System.exit(1);
		}
		
		is_running = true;
	}
	
	public void subscribeInstruments(String str) {
		Set<Instrument> instrumentSet = new HashSet<Instrument>();
		String [] iststr = str.split(",");
        for (int ii=0; ii<iststr.length; ii++) {
			if (! iststr[ii].isEmpty()) {
				instrumentSet.add(Instrument.fromString(iststr[ii]));
			}
        }
		log.info("Subscribing instruments ("+str+")");
		notifyLog("Subscribing instruments ("+str+")");
		try {
			context.setSubscribedInstruments(instrumentSet, true);
			log.info(context.getSubscribedInstruments().toString());
		} catch (Exception e) {
			log.info(e.toString());
			notifyFatal(e.toString());
			System.exit(1);
		}
		
		Map<String, String> returnInfo = getHashMap("pip");
		for (Instrument inst: instrumentSet) {
			returnInfo.put(inst.toString().replace("/", "_"), String.valueOf(inst.getPipValue()));
		}
		notifyToPython(returnInfo);
	}

	public void doOrder(HashMap<String,String> hm) {
		String cmd = hm.get("cmd");
		String failStr = "";
		IOrder order = null;
		try {
			Future<IOrder> future = context.executeTask(new OrderTask(order_types.get(cmd), hm));
			order = future.get();
		} catch (Exception e) {
			e.printStackTrace();
			failStr = e.toString();
		}
		
		Map<String, String> returnInfo = getHashMap("report");
		returnInfo.put("order", cmd);
		if (order != null) {
			returnInfo.put("info", order.toString());
		} else {
			returnInfo.put("info", "fail:"+failStr);
		}
		notifyToPython(returnInfo);
	}

	public void modifyOrder(HashMap<String,String> hm) {
		log.info("modifyOrder()");
		String failStr = "";
		IOrder order = null;
		try {
			Future<IOrder> future = context.executeTask(new ModifyOrderTask(hm));
			order = future.get();
		} catch (Exception e) {
			e.printStackTrace();
			failStr = e.toString();
		}

		Map<String, String> returnInfo = getHashMap("report");
		returnInfo.put("order", "modifyorder");
		if (order != null) {
			returnInfo.put("info", order.toString());
		} else {
			returnInfo.put("info", "fail:"+failStr);
		}
		notifyToPython(returnInfo);
	}
	
	public void closeOrder(HashMap<String,String> hm) {
		log.info("closeOrder()");
		try {
			context.executeTask(new CloseTask(hm)).get();
		} catch (Exception e) {
			e.printStackTrace();
			Map<String, String> returnInfo = getHashMap("report");
			returnInfo.put("info", "fail:"+e.toString());
		}
		Map<String, String> returnInfo = getHashMap("report");
		returnInfo.put("cmd", hm.get("cmd"));
		returnInfo.put("done", "true");
		notifyToPython(returnInfo);
	}
	
	public void getOrders(HashMap<String,String> hm) {
		log.info("getorders()");
		List<IOrder> orders = null;
		try {
			orders = engine.getOrders();
		} catch (JFException e) {
			e.printStackTrace();
		}
		Map<String, Map<String, String>> result = new HashMap<String, Map<String, String>>();
		for (IOrder o: orders) {
			Map<String, String> entry = new HashMap<String, String>();
			entry.put("id", o.getId());
			entry.put("status", o.getState().toString());
			if (o.isLong()) {
				entry.put("side", "long");
			} else {
				entry.put("side", "short");
			}
			entry.put("instrument", o.getInstrument().toString().replace("/", "_"));
			entry.put("price", String.valueOf(o.getOpenPrice()));
			entry.put("volume", String.valueOf(o.getAmount()));
			entry.put("req_volume", String.valueOf(o.getRequestedAmount()));
			entry.put("tp", String.valueOf(o.getTakeProfitPrice()));
			entry.put("sl", String.valueOf(o.getStopLossPrice()));
			long creationtime = o.getCreationTime();
			long goodtime = o.getGoodTillTime() - creationtime;
			if (goodtime < 0) { goodtime = 0; }
			entry.put("creation_time", String.valueOf(creationtime));
			entry.put("days", String.valueOf(TimeUnit.MILLISECONDS.toDays(goodtime)));
			result.put(o.getLabel(), entry);
		}
		Map<String, String> returnInfo = getHashMap("report");
		returnInfo.put("cmd", hm.get("cmd"));
		returnInfo.put("info", result.toString());
		notifyToPython(returnInfo);
	}

	public void getTick(HashMap<String,String> hm) {
		if (!hm.containsKey("instrument")) {
			Map<String, String> returnInfo = getHashMap("report");
			returnInfo.put("cmd", hm.get("cmd"));
			returnInfo.put("error", "no instrument specified");
			notifyToPython(returnInfo);
			return;
		}
		ITick tick;
		try {
			tick = history.getLastTick(Instrument.fromString(hm.get("instrument").replaceAll("_", "/")));
		} catch (JFException e) {
			e.printStackTrace();
			Map<String, String> returnInfo = getHashMap("report");
			returnInfo.put("cmd", hm.get("cmd"));
			returnInfo.put("error", e.toString());
			notifyToPython(returnInfo);
			return;
		}
		Map<String, String> returnInfo = getHashMap("report");
		returnInfo.put("cmd", hm.get("cmd"));
		returnInfo.put("ask", String.valueOf(tick.getAsk()));
		returnInfo.put("bid", String.valueOf(tick.getBid()));
		returnInfo.put("ask_volume", String.valueOf(tick.getAskVolume()));
		returnInfo.put("bid_volume", String.valueOf(tick.getBidVolume()));
		returnInfo.put("time", String.valueOf(tick.getTime()));
		returnInfo.put("instrument", hm.get("instrument").replaceAll("_", "/"));
		notifyToPython(returnInfo);
	}

	public void checkOnline(HashMap<String,String> hm) {
		long time = Long.valueOf(hm.get("time"));
		try {
			Map<String, String> returnInfo = getHashMap("report");
			returnInfo.put("cmd", hm.get("cmd"));
			String online = isOnline(time) ? "true" : "false";
			returnInfo.put("isonline", online);
			notifyToPython(returnInfo);
		} catch (JFException e) {
			e.printStackTrace();
			Map<String, String> returnInfo = getHashMap("report");
			returnInfo.put("cmd", hm.get("cmd"));
			returnInfo.put("error", e.toString());
			notifyToPython(returnInfo);
		}
	}

	public void setConfigurations(HashMap<String,String> hm) {
		if (hm.containsKey("tick") && hm.get("tick").equals("on"))
			flag_get_tick = true;
		else
			flag_get_tick = false;
		if (hm.containsKey("bar") && hm.get("bar").equals("on"))
			flag_get_bar = true;
		else
			flag_get_bar = false;
		if (hm.containsKey("instruments")) {
			subscribeInstruments(hm.get("instruments"));
		}
		
	}

	public void stopStrategy(HashMap<String,String> hm) {
		client.stopStrategy(strategyID);
	}

	private class OrderTask implements Callable<IOrder> {
		public static final int TYPE_MARKET_ORDER = 0;
		public static final int TYPE_LIMIT_ORDER = 1;
		public static final int TYPE_STOP_ORDER = 2;
	    private final String ticket_id;
	    private final OrderCommand side;
	    private final Instrument instrument;
	    private final double volume;
	    private final double price;
	    private final double slippage;
	    private final double takeProfit;
	    private final double stopLoss;
	    private final double pip_unit;
	    private long goodTime_days = -1;

	    public OrderTask(int type, HashMap<String,String> hm) {
			this.ticket_id = hm.get("ticket_id");
			this.instrument = Instrument.fromString(hm.get("instrument").replaceAll("_", "/"));
			if (type == TYPE_MARKET_ORDER) {
				this.side = hm.get("side").equals("buy") ? OrderCommand.BUY : OrderCommand.SELL;
			} else if (type == TYPE_LIMIT_ORDER) {
				this.side = hm.get("side").equals("buy") ? OrderCommand.BUYLIMIT : OrderCommand.SELLLIMIT;
				this.goodTime_days = hm.containsKey("days") ? Long.valueOf(hm.get("days")) : -1;
			} else if (type == TYPE_STOP_ORDER) {
				this.side = hm.get("side").equals("buy") ? OrderCommand.BUYSTOP : OrderCommand.SELLSTOP;
				this.goodTime_days = hm.containsKey("days") ? Long.valueOf(hm.get("days")) : -1;
			} else {
				this.side = null;
			}
			this.volume = Double.valueOf(hm.get("volume"));
			this.price = hm.containsKey("price") ? Double.valueOf(hm.get("price")) : 0;
			this.slippage = hm.containsKey("slippage") ? Double.valueOf(hm.get("slippage")) : DEFAULT_SLIPPAGE;
			this.takeProfit = hm.containsKey("sl_pips") ? Double.valueOf(hm.get("sl_pips")) : 0;
			this.stopLoss = hm.containsKey("tp_pips") ? Double.valueOf(hm.get("tp_pips")) : 0;
			this.pip_unit = this.instrument.getPipValue();
	    }

	    public IOrder call() throws Exception {
	    	IOrder order;
	    	if (price > 0) {
	    		order = engine.submitOrder(ticket_id, instrument, side, volume, price, slippage);
	    	} else {
	    		order = engine.submitOrder(ticket_id, instrument, side, volume);
	    	}
	    	order.waitForUpdate(10000);
	    	if (goodTime_days > 0) {
	    		long currentTime = System.currentTimeMillis();
	    		order.setGoodTillTime(currentTime + goodTime_days * 86400000);
	    		order.waitForUpdate(10000);
	    	}
    		double openPrice = order.getOpenPrice();
	    	if (takeProfit >= 0) {
	    		if (order.isLong()) {
	    			order.setTakeProfitPrice(openPrice + takeProfit * pip_unit);
	    		} else {
	    			order.setTakeProfitPrice(openPrice - takeProfit * pip_unit);
	    		}
	    		order.waitForUpdate(10000);
		    }
	    	if (stopLoss >= 0) {
	    		if (order.isLong()) {
	    			order.setStopLossPrice(openPrice - stopLoss * pip_unit);
	    		} else {
	    			order.setStopLossPrice(openPrice + stopLoss * pip_unit);
	    		}
	    	}
	        return order;
	    }
	}

	private class ModifyOrderTask implements Callable<IOrder> {
	    private final String ticket_id;

	    private final double volume;
	    private final double price;
	    private final double takeProfit;
	    private final double stopLoss;
	    private long goodTime_days = -1;

	    public ModifyOrderTask(HashMap<String,String> hm) {
			this.ticket_id = hm.get("ticket_id");
			this.volume = hm.containsKey("volume") ? Double.valueOf(hm.get("volume")) : 0;
			this.price = hm.containsKey("price") ? Double.valueOf(hm.get("price")) : 0;
			this.takeProfit = hm.containsKey("sl_pips") ? Double.valueOf(hm.get("sl_pips")) : 0;
			this.stopLoss = hm.containsKey("tp_pips") ? Double.valueOf(hm.get("tp_pips")) : 0;
			this.goodTime_days = hm.containsKey("days") ? Long.valueOf(hm.get("days")) : -1;
		}

	    public IOrder call() throws Exception {
	    	IOrder order = engine.getOrder(ticket_id);
	    	if (order.getState() == IOrder.State.CREATED && price > 0) {
	    		order.setOpenPrice(price);
		    	order.waitForUpdate(10000);
	    	}
	    	if (order.getState() == IOrder.State.CREATED && volume > 0) {
	    		order.setRequestedAmount(volume);
	    		order.waitForUpdate(10000);
	    	}
	    	double openPrice = order.getOpenPrice();
	    	double pip_unit = order.getInstrument().getPipValue();
    		if (takeProfit >= 0) {
    			if (order.isLong()) {
    				order.setTakeProfitPrice(openPrice + takeProfit * pip_unit);
    			} else {
    				order.setTakeProfitPrice(openPrice - takeProfit * pip_unit);
    			}
        		order.waitForUpdate(10000);
    		}
    		if (stopLoss >= 0) {
    			if (order.isLong()) {
    				order.setStopLossPrice(openPrice - stopLoss * pip_unit);
    			} else {
    			order.setStopLossPrice(openPrice + stopLoss * pip_unit);
    			}
        		order.waitForUpdate(10000);
    		}
	    	if (goodTime_days > 0) {
	    		long creationTime = order.getCreationTime();
	    		order.setGoodTillTime(creationTime + goodTime_days * 86400000);
	    		order.waitForUpdate(10000);
	    	}
	        return order;
	    }
	}

	private class CloseTask implements Callable<Void> {
	    private final String ticket_id;
	    private final double volume;

	    public CloseTask(HashMap<String,String> hm) {
			this.ticket_id = hm.get("ticket_id");
			this.volume = hm.containsKey("volume") ? Double.valueOf(hm.get("volume")) : 0;
	    }

	    public Void call() throws Exception {
	    	if (volume > 0) {
	    		engine.getOrder(ticket_id).close(volume);
	    	} else {
	    		engine.getOrder(ticket_id).close();
	    	}
			return null;
	    }
	}

	@SuppressWarnings("unused")
	private double getMinAmount(Instrument instrument){
	    switch (instrument){
	        case XAUUSD : return 0.000001;
	        case XAGUSD : return 0.00005;
	        default : return 0.001;
	    }
	}

	/**
	 * for py4j
	 */
    public void registerListener(PythonListener listener) {
		plistener = listener;
    }

    public void notifyToPython(Object obj) {
		plistener.notify(obj);
    }
    

    private void notifyFatal(String str) {
    	Map<String, String> returnInfo = getHashMap("fail");
    	returnInfo.put("info", str);
    	notifyToPython(returnInfo);
    }
    
	public void sendCommand(HashMap<String,String> hm) {
		log.info("sendCommand("+hm.get("cmd")+")");
		String cmd = hm.get("cmd");
		if (!cmd_mappings.containsKey(cmd)) {
			Map<String, String> returnInfo = getHashMap("report");
			returnInfo.put("cmd", hm.get("cmd"));
			returnInfo.put("error", "no such command");
			notifyToPython(returnInfo);
			return;
		}
		try {
			cmd_mappings.get(cmd).invoke(this, hm);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void notifyLog(String str) {
    	Map<String, String> returnInfo = getHashMap("log");
    	returnInfo.put("info", str);
    	notifyToPython(returnInfo);
    }

	/**
	 * Main
	 */
    public static void main(String[] args) {
		JForexGateway application = new JForexGateway();
		try {
			application.setup_cmd_method_mapping();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		//--- for py4j
		GatewayServer server = new GatewayServer(application);
		server.start(true);
		log.info("start..");
	}
}
