# -*- coding: utf-8 -*-
import sys, os, time, re, ast
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
                           'takeProfit':str(takeProfit),'stopLoss':str(stopLoss)})

    def limit_order(self, ticket_id, instrument, side, volume, price, slippage=5, takeProfit=-1, stopLoss=-1):
        self.send_command({'cmd':'limitorder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'price':str(price), 'slippage':str(slippage),
                           'tp':str(takeProfit), 'sl':str(stopLoss)})

    def stop_order(self, ticket_id, instrument, side, volume, price, slippage=5, takeProfit=-1, stopLoss=-1):
        self.send_command({'cmd':'stoporder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'price':str(price), 'slippage':str(slippage),
                           'tp':str(takeProfit), 'sl':str(stopLoss)})

    def mit_order(self, ticket_id, instrument, side, volume, price, slippage=5, takeProfit=-1, stopLoss=-1):
        self.send_command({'cmd':'mitorder', 'ticket_id':ticket_id,
                           'instrument':instrument, 'side':side,
                           'volume':str(volume), 'price':str(price), 'slippage':str(slippage),
                           'tp':str(takeProfit), 'sl':str(stopLoss)})

    def modify_order(self, ticket_id, volume, price, takeProfit=-1, stopLoss=-1):
        self.send_command({'cmd':'modifyorder', 'ticket_id':ticket_id,
                           'volume':str(volume), 'price':str(price),
                           'tp':str(takeProfit), 'sl':str(stopLoss)})

    def close_order(self, ticket_id, volume=0):
        self.send_command({'cmd':'closeorder', 'ticket_id':ticket_id, 'volume':str(volume)})

    def close(self, ticket_id):
        self.send_command({'cmd':'close', 'ticket_id':ticket_id})



if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: %s [username] [password]\n" %(sys.argv[0]))
        os._exit(0)
    dc = Dukascopy(sys.argv[1], sys.argv[2])

    while True:
        line = input("input> ")
        if line == "quit":
            break
        elif line == "help":
            print("input must be dictionary format like {'cmd': 'xxx', 'arg1': 'aaa'...}\n")
            continue
        elif line == "":
            continue
        cmd = re.split('\s+', line.strip())
        if cmd[0] == 'stop':
            dc.stop()
        elif cmd[0] == 'get_tick':
            dc.get_tick(cmd[1])
        elif cmd[0] == 'get_orders':
            dc.get_orders()
        elif cmd[0] == 'set_conf':
            tick, bar, instruments = cmd[1:]
            dc.set_conf(tick=tick, bar=bar, instruments=instruments)
        elif cmd[0] == 'market_order':
            print(len(cmd))
            for i in range(7-len(cmd)): cmd.append(-1)
            print(cmd[1:])
            ticket_id, instrument, side, volume, tp, sl = cmd[1:]
            dc.market_order(ticket_id, instrument, side, volume, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'limit_order':
            for i in range(7-len(cmd)): cmd.append(-1)
            ticket_id, instrument, side, volume, price, tp, sl = cmd[1:]
            dc.limit_order(ticket_id, instrument, side, volume, price, takeProfit=tp, stopLoss=sl)
        elif cmd[0] == 'stop_order':
            for i in range(7-len(cmd)): cmd.append(-1)
            ticket_id, instrument, side, volume, price, tp, sl = cmd[1:]
            dc.market_order(ticket_id, instrument, side, volume, price, takeProfit=tp, stopLoss=sl)

        print("--------------------")

    dc.disconnect()
