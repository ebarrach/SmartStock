import sys,asyncio,os
from pathlib import Path
from amqtt.broker import Broker
async def main():
    port=os.environ.get('TEST_MQTT_PORT','18893')
    broker=Broker({'listeners':{'default':{'type':'tcp','bind':f'127.0.0.1:{port}'}},'plugins':{'amqtt.plugins.authentication.AnonymousAuthPlugin':{'allow_anonymous':True}}})
    await broker.start()
    print(f'TEST BROKER READY 127.0.0.1:{port}',flush=True)
    try:await asyncio.Event().wait()
    finally:await broker.shutdown()
asyncio.run(main())
