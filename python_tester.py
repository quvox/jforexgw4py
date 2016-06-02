# -*- coding: utf-8 -*-
import sys, os, code, readline, time, re, ast, atexit
from py4j.java_gateway import JavaGateway, CallbackServerParameters

INSTRUMENTS = "USD_JPY,EUR_JPY,EUR_USD"

class PythonListener(object):

    def __init__(self, gateway):
        print("init...")
        self.gateway = gateway

    def notify(self, obj):
        print(obj)
        if obj["type"] == "fail": os._exit(1)
        if obj["type"] == "on_stop": os._exit(1)

    class Java:
        implements = ["net.quvox.jforexgw4py.PythonListener"]


class Dukascopy():
    def __init__(self, user, pswd):
        try:
            self.gateway = JavaGateway(callback_server_parameters=CallbackServerParameters())
            self.listener = PythonListener(self.gateway)
            self.gateway.entry_point.registerListener(self.listener)
        except:
            print("JForexGateway4py (java) is not running.....")
            os._exit(1)
        time.sleep(1)
        self.start(user, pswd)

    def disconnect(self):
        self.gateway.shutdown()

    def send_command(self, cmd):
        m = self.createHashMap(cmd)
        self.gateway.entry_point.sendCommand(m)

    def createHashMap(self, info):
        m = self.gateway.jvm.java.util.HashMap()
        for k,v in info.items():
            m.put(k, v)
        return m

    def start(self, user, pswd):
        self.send_command({"cmd": "start", "user": user, "pass": pswd, "instruments": INSTRUMENTS})

    def stop(self):
        self.send_command({"cmd": "stop"})

    def get_orders(self):
        self.send_command({'cmd':'getorders'})

    def get_tick(self, instrument):
        self.send_command({'cmd':'tick', 'instrument': instrument})

    def set_conf(self, tick="on", bar="on", instruments=""):
        self.send_command({'cmd':'set', 'tick':tick, 'bar':bar, 'instruments':instruments})

    def market_order(self, ticket_id, instrument, side, volume, price=0, slippage=5, takeProfit=-1, stopLoss=-1):
        self.send_command({'cmd':'marketorder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'slippage':str(slippage),
                           'tp_pips':str(takeProfit),'sl_pips':str(stopLoss)})

    def limit_order(self, ticket_id, instrument, side, volume, price, slippage=5, takeProfit=-1, stopLoss=-1, days=1):
        self.send_command({'cmd':'limitorder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'price':str(price), 'slippage':str(slippage),
                           'tp_pips':str(takeProfit), 'sl_pips':str(stopLoss),
                           'days':str(days)})

    def stop_order(self, ticket_id, instrument, side, volume, price, slippage=5, takeProfit=-1, stopLoss=-1, days=1):
        self.send_command({'cmd':'stoporder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'price':str(price), 'slippage':str(slippage),
                           'tp_pips':str(takeProfit), 'sl_pips':str(stopLoss),
                           'days':str(days)})

    def mit_order(self, ticket_id, instrument, side, volume, price, slippage=5, takeProfit=-1, stopLoss=-1, days=1):
        self.send_command({'cmd':'mitorder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'price':str(price), 'slippage':str(slippage),
                           'tp_pips':str(takeProfit), 'sl_pips':str(stopLoss),
                           'days':str(days)})

    def modify_order(self, ticket_id, volume, price, takeProfit=-1, stopLoss=-1, days=1):
        self.send_command({'cmd':'modifyorder', 'ticket_id':ticket_id,
                           'volume':str(volume), 'price':str(price),
                           'tp_pips':str(takeProfit), 'sl_pips':str(stopLoss),
                           'days':str(days)})

    def cancel_order(self, ticket_id, volume=0):
        self.send_command({'cmd':'modifyorder', 'ticket_id':ticket_id, 'volume':str(volume)})

    def close(self, ticket_id, volume=0):
        self.send_command({'cmd':'close', 'ticket_id':ticket_id, 'volume':str(volume)})

    def is_online(self, tm):
        self.send_command({'cmd':'isonline', 'time':tm})

class MyConsole(code.InteractiveConsole):
    def __init__(self, local=None, filename="<console>",
                 histfile=os.path.expanduser("~/.python-myhistory")):
        code.InteractiveConsole.__init__(self, local, filename)
        self.init_history(histfile)

    def init_history(self, histfile):
        readline.parse_and_bind("tab: complete")
        if hasattr(readline, "read_history_file"):
            try:
                readline.read_history_file(histfile)
            except IOError:
                pass
            atexit.register(self.save_history, histfile)

    def save_history(self, histfile):
        readline.write_history_file(histfile)
    
    def push(self, line):
        if line == "quit":
            os._exit(0)
        elif line == "help":
            self.show_help()
            return
        elif line == "":
            return
        
        cmd = re.split('\s+', line.strip())
        if cmd[0] == 'stop':
            dc.stop()
        elif cmd[0] == 'get_tick':
            if len(cmd) < 2: return
            dc.get_tick(cmd[1])
        elif cmd[0] == 'get_orders':
            dc.get_orders()
        elif cmd[0] == 'set_conf':
            if len(cmd) != 4: return
            tick, bar, instruments = cmd[1:]
            dc.set_conf(tick=tick, bar=bar, instruments=instruments)
        elif cmd[0] == 'market_order':
            if len(cmd) > 7: return
            for i in range(7-len(cmd)): cmd.append(-1)
            ticket_id, instrument, side, volume, tp, sl = cmd[1:]
            dc.market_order(ticket_id, instrument, side, volume, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'limit_order':
            if len(cmd) > 8: return
            for i in range(8-len(cmd)): cmd.append(-1)
            ticket_id, instrument, side, volume, price, tp, sl = cmd[1:]
            dc.limit_order(ticket_id, instrument, side, volume, price, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'stop_order':
            for i in range(8-len(cmd)): cmd.append(-1)
            ticket_id, instrument, side, volume, price, tp, sl = cmd[1:]
            dc.market_order(ticket_id, instrument, side, volume, price, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'mit_order':
            for i in range(8-len(cmd)): cmd.append(-1)
            ticket_id, instrument, side, volume, price, tp, sl = cmd[1:]
            dc.mit_order(ticket_id, instrument, side, volume, price, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'modify_order':
            for i in range(6-len(cmd)): cmd.append(-1)
            ticket_id, volume, price, tp, sl = cmd[1:]
            dc.modify_order(ticket_id, volume, price, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'close':
            for i in range(3-len(cmd)): cmd.append(0)
            dc.close(cmd[1], volume=cmd[2])
        elif cmd[0] == 'is_online':
            dc.is_online(cmd[1])

    def show_help(self):
        print("usage: (input)>> command arg1 arg2 ...")
        print("Examples:")
        print("stop                                     # stop jforexgateway4py")
        print("get_tick USD_JPY                         # get the last tick for the instrument")
        print("get_orders                               # get all orders and positions")
        print("set_conf on/off on/off USD_JPY,EUR_JPY   # tick, bar, instruments (comma separated)")
        print("market_order label USD_JPY 0.1 30 20     # ticket_id, instruments, amount, takeProfit(pips, optional), stopLoss(pips, optional)")
        print("limit_order label USD_JPY 0.1 100 30 20  # ticket_id, instruments, amount, price, takeProfit(pips, optional), stopLoss(pips, optional)")
        print("stop_order label USD_JPY 0.1 100 30 20   # ticket_id, instruments, amount, price, takeProfit(pips, optional), stopLoss(pips, optional)")
        print("mit_order label USD_JPY 0.1 100 30 20    # ticket_id, instruments, amount, price, takeProfit(pips, optional), stopLoss(pips, optional)")
        print("modify_order label 0.1 100 30 20         # ticket_id, amount, price, takeProfit(pips, optional), stopLoss(pips, optional)")
        print("close label 0.05                         # ticket_id, amount (optional, close all if not specified)")
        print("is_online 1464882582                     # unixtime (check if the market is open at the specified time)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: %s [username] [password]\n" %(sys.argv[0]))
        os._exit(0)
    dc = Dukascopy(sys.argv[1], sys.argv[2])

    my_console = MyConsole()
    sys.ps1 = "(input)>> "
    sys.ps2 = "------->> "
    my_console.interact("### welcome to my console!!! ###")
    dc.disconnect()
