"""Loopback-only USB recipe. No MQTT or scale dependency, no stock logic here.

Each armed URL carries a fixed operation UUID. Reuse it for retries; explicitly
create a new URL/form for another intentional scan. Never rotate on receipt.
"""
import json
import os
import secrets
import threading
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs
from urllib.request import Request, urlopen
from uuid import uuid4
from gs1_decoder import decode_gs1, GS1Error

def api_request(base, path, payload=None, operation=None):
    headers={'Content-Type':'application/json'}
    if operation: headers['X-Command-Id']=operation
    request=Request(base+path,data=None if payload is None else json.dumps(payload).encode('utf8'),headers=headers)
    try:
        with urlopen(request,timeout=8) as response:return response.status,json.load(response)
    except HTTPError as error:
        return error.code,json.load(error)

def content(raw, content_type):
    text=raw.decode('utf8',errors='strict')
    if content_type.split(';')[0]=='application/json':
        values=json.loads(text)
        if not isinstance(values,dict) or set(values) not in ({'content'},{'code'}):raise ValueError('JSON content/code unique requis')
        result=next(iter(values.values()))
    elif content_type.startswith('text/plain'): result=text
    else:
        # Fail closed if QRbot inserted unescaped form delimiters. Browser form
        # percent-encodes automatically; JSON/text preserve ASCII29 too.
        if '+' in text:raise ValueError('Plus non encode : utiliser le formulaire local ou JSON')
        values=parse_qs(text,keep_blank_values=True,strict_parsing=True)
        if set(values) not in ({'content'},{'code'}) or len(next(iter(values.values())))!=1:
            raise ValueError('Champ content unique requis ; verifier encodage form')
        result=next(iter(values.values()))[0]
    if not isinstance(result,str) or not 1<=len(result)<=2048:raise ValueError('Code absent ou trop long')
    return result

def make_server(base, port=8083, token=None):
    token=token or secrets.token_urlsafe(24)
    sessions={}
    lock=threading.Lock()
    prefix='/'+token
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*_args): pass
        def send(self,status,value,html=False):
            data=value.encode('utf8') if html else json.dumps(value,ensure_ascii=False).encode('utf8')
            self.send_response(status)
            self.send_header('Content-Type','text/html; charset=utf-8' if html else 'application/json; charset=utf-8')
            self.send_header('Content-Length',str(len(data)))
            self.send_header('Cache-Control','no-store')
            self.send_header('Referrer-Policy','no-referrer')
            self.send_header('X-Content-Type-Options','nosniff')
            self.end_headers();self.wfile.write(data)
        def do_GET(self):
            if self.path=='/health':
                try: status,data=api_request(base,'/api/v1/scan-only-test');self.send(status,data)
                except (URLError,TimeoutError,OSError):self.send(503,{'code':'api_unavailable'})
                return
            if self.path==prefix or self.path.startswith(prefix+'/op/'):
                op=self.path.removeprefix(prefix+'/op/') if self.path.startswith(prefix+'/op/') else None
                with lock: direction=sessions.get(op)
                page=f'''<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>SmartStock — test USB</title>
<body style="font:18px system-ui;max-width:700px;margin:24px auto;padding:16px"><h1>Test sans balance</h1>
<p>Balance déclarée absente par le profil PC. Aucun poids n'est simulé. API : {escape(base)}</p>
<form method="post" action="{prefix}/arm"><label>Nouvelle opération volontaire <select name="direction"><option value="auto">Selon présence connue</option><option value="entry">Ajout explicite</option><option value="exit">Retrait explicite</option></select></label><button>Armer une nouvelle opération</button></form>'''
                if direction:
                    endpoint=f'/scan/{token}/{op}'
                    page+=f'''<p>Opération : {op} ; direction : {direction}</p><p>QRbot Business POST : <code>http://127.0.0.1:{self.server.server_port}{endpoint}</code><br>Corps : <code>content=[code]</code></p>
<p>Android sans Business : scanner dans QRbot, copier le contenu exact puis le coller ici.</p>
<form id="scan" method="post" action="{endpoint}"><label>Contenu DataMatrix <textarea name="content" rows="5" style="width:100%" required></textarea></label><button>Envoyer cette opération</button></form><pre id="result" style="white-space:pre-wrap"></pre>
<p>Un retry de cette URL conserve l'opération. Pour un nouveau scan volontaire, revenir ici et armer une nouvelle opération ; utiliser sa nouvelle URL QRbot.</p>'''
                page+='''<script>const f=document.getElementById('scan');if(f)f.addEventListener('submit',async e=>{e.preventDefault();try{const r=await fetch(f.action,{method:'POST',headers:{'Content-Type':'text/plain;charset=UTF-8'},body:f.elements.content.value});document.getElementById('result').textContent=JSON.stringify(await r.json(),null,2)}catch(e){document.getElementById('result').textContent='API inaccessible : reessayer la MEME operation'}});</script>'''
                self.send(200,page+'</body></html>',True)
            else:self.send(404,{'code':'not_found'})
        def do_POST(self):
            try:
                size=int(self.headers.get('Content-Length','0'))
                if not 0<size<=8192:raise ValueError('Taille invalide')
                raw=self.rfile.read(size)
                if self.path==prefix+'/arm':
                    params=parse_qs(raw.decode('ascii'),strict_parsing=True)
                    if set(params)!={'direction'} or params['direction'] not in (['auto'],['entry'],['exit']):raise ValueError('Direction invalide')
                    op=str(uuid4())
                    with lock:sessions[op]=params['direction'][0]
                    self.send_response(303);self.send_header('Location',prefix+'/op/'+op);self.send_header('Content-Length','0');self.end_headers();return
                path='/scan/'+token+'/'
                if not self.path.startswith(path):self.send(404,{'code':'not_found'});return
                op=self.path.removeprefix(path)
                with lock: direction=sessions.get(op)
                if direction is None:self.send(410,{'code':'operation_not_armed','message':'Armer une nouvelle operation ; ne pas recreer un retry avec un nouvel ID'});return
                fields=decode_gs1(content(raw,self.headers.get('Content-Type','')),require_serial=False)
                status,data=api_request(base,'/api/v1/scan-only-test',dict(fields,direction=direction),op)
                self.send(status,data)
            except (ValueError,UnicodeError,GS1Error) as error:self.send(422,{'code':'invalid_scan','message':str(error)})
            except (URLError,TimeoutError,OSError):self.send(503,{'code':'api_unavailable','message':'Reessayer la MEME URL/opération ; aucun rearmement automatique'})
    server=ThreadingHTTPServer(('127.0.0.1',port),Handler)
    server.recipe_token=token
    return server

def main():
    base=os.environ.get('SMARTSTOCK_API_URL','http://127.0.0.1:18119').rstrip('/')
    server=make_server(base,int(os.environ.get('SMARTSTOCK_QRBOT_PORT','8083')))
    print(f'Passerelle USB locale http://127.0.0.1:{server.server_port}/{server.recipe_token}',flush=True)
    print(f'API SmartStock : {base}\nGET /health pour etat du mode. POST scan JSON, text/plain ou form content=[code].',flush=True)
    print('Armer sur la page locale pour obtenir URL QRbot exacte, stable par operation. Android sans Business : copier/coller QRbot dans ce formulaire. Aucun MQTT requis.',flush=True)
    try:
        print('Etat API/mode :',api_request(base,'/api/v1/scan-only-test'),flush=True)
        server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()

if __name__=='__main__':main()
