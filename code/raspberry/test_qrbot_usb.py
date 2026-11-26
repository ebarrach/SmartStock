import json
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from unittest.mock import patch
from qrbot_usb import content, make_server
from gs1_decoder import decode_gs1, GS1Error

CODE='(01)05400711223344(17)291231(10)USB(21)S1'

class UsbTests(unittest.TestCase):
    def test_ascii29_and_special_characters_are_preserved(self):
        value='01054007112233441729123110USB\x1d21A+B&C'
        self.assertEqual(content(urlencode({'content':value}).encode(),'application/x-www-form-urlencoded'),value)
        self.assertEqual(content(json.dumps({'content':value}).encode(),'application/json'),value)
        self.assertEqual(content(value.encode(),'text/plain'),value)
        with self.assertRaises(ValueError):content(b'content=X&bad=Y','application/x-www-form-urlencoded')
        with self.assertRaises(ValueError):content(b'content=A+B','application/x-www-form-urlencoded')
    def test_non_serialized_code_requires_explicit_action_downstream(self):
        code='(01)05400711223344(17)291231(10)USB'
        with self.assertRaises(GS1Error):decode_gs1(code)
        self.assertNotIn('serial',decode_gs1(code,require_serial=False))
    def test_retry_reuses_operation_but_voluntary_rearm_creates_another(self):
        server=make_server('http://127.0.0.1:9',0,'test-token')
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        base=f'http://127.0.0.1:{server.server_port}'
        calls=[]
        def api(_base,_path,payload=None,operation=None):
            calls.append((operation,payload))
            return 200,{'mode':'test sans balance','operationId':operation}
        def arm():
            with urlopen(Request(base+'/test-token/arm',data=b'direction=auto'),timeout=3) as r:
                return r.url.rsplit('/',1)[1]
        def scan(op):
            with urlopen(Request(base+'/scan/test-token/'+op,data=CODE.encode(),headers={'Content-Type':'text/plain'}),timeout=3) as r:return json.load(r)
        try:
            with patch('qrbot_usb.api_request',side_effect=api):
                first=arm();scan(first);scan(first)
                second=arm();scan(second)
                self.assertEqual(calls[0][0],calls[1][0])
                self.assertNotEqual(calls[0][0],calls[2][0])
                self.assertEqual(calls[0][1],calls[2][1])
        finally:server.shutdown();server.server_close();thread.join(3)

if __name__=='__main__':unittest.main()
