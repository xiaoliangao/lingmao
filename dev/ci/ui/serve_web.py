"""Serve an already built app and proxy its real API on loopback for smoke tests."""
import http.server,os,sys,urllib.request,urllib.error
from functools import partial
class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self,*args): pass
    def api(self):
        raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
        headers={k:v for k,v in self.headers.items() if k.lower() not in {'connection','content-length','accept-encoding'}}
        req=urllib.request.Request('http://127.0.0.1:18080'+self.path,data=raw if raw else None,headers=headers,method=self.command)
        try: response=urllib.request.urlopen(req,timeout=30)
        except urllib.error.HTTPError as error: response=error
        body=response.read();self.send_response(response.status)
        for key,val in response.headers.items():
            if key.lower() not in {'connection','transfer-encoding','content-length','content-encoding'}: self.send_header(key,val)
        self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
    def do_GET(self):
        if self.path.startswith(('/api/','/files/')): self.api();return
        if not os.path.isfile(self.translate_path(self.path)): self.path='/index.html'
        super().do_GET()
    def do_POST(self): self.api()
    def do_PUT(self): self.api()
    def do_DELETE(self): self.api()
    def do_PATCH(self): self.api()
http.server.ThreadingHTTPServer(('127.0.0.1',18081),partial(Handler,directory=sys.argv[1])).serve_forever()
