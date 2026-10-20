from pathlib import Path
import json,threading,http.server,time,socket,struct,zlib,uuid,subprocess,shutil,re
ROOT=Path(__file__).resolve().parents[1]; BASE=ROOT/'test-velocity'; TEST=BASE/('run-'+uuid.uuid4().hex[:8])
sent=[];updates=[];guard=threading.Lock()
class API(http.server.BaseHTTPRequestHandler):
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])));method=self.path.rsplit('/',1)[-1];result=True
  if method=='getMe':result={'id':999,'is_bot':True,'username':'ArtificalTestBot','first_name':'Test'}
  elif method=='getUpdates':
   with guard:result=[u for u in updates if u['update_id']>=body.get('offset',0)]
   if not result:time.sleep(.15)
  elif method=='sendMessage':
   sent.append(body);result={'message_id':len(sent),'chat':{'id':body['chat_id'],'type':'private'},'date':int(time.time())}
  data=json.dumps({'ok':True,'result':result}).encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)
 def log_message(self,*a):pass
def vi(n):
 out=b''
 while True:
  part=n&127;n>>=7;out+=bytes([part|(128 if n else 0)])
  if not n:return out
def st(s):
 raw=s.encode();return vi(len(raw))+raw
def var(read):
 n=0
 for shift in range(0,35,7):
  b=read(1)[0];n|=(b&127)<<shift
  if b<128:return n
 raise ValueError('varint')
class Buf:
 def __init__(self,b):self.b=b;self.pos=0
 def read(self,n):
  out=self.b[self.pos:self.pos+n];self.pos+=n
  if len(out)!=n:raise EOFError()
  return out
def nbt(b,t=None):
 if t is None:t=b.read(1)[0]
 if t==0:return None
 if t in [1,2,3,4,5,6]:
  fmt={1:'>b',2:'>h',3:'>i',4:'>q',5:'>f',6:'>d'}[t];return struct.unpack(fmt,b.read(struct.calcsize(fmt)))[0]
 if t==8:return b.read(struct.unpack('>H',b.read(2))[0]).decode()
 if t==9:
  child=b.read(1)[0];count=struct.unpack('>i',b.read(4))[0];return [nbt(b,child) for _ in range(count)]
 if t==10:
  out={}
  while True:
   child=b.read(1)[0]
   if not child:return out
   key=b.read(struct.unpack('>H',b.read(2))[0]).decode();out[key]=nbt(b,child)
 raise ValueError('tag '+str(t))
def flatten(v):
 if isinstance(v,str):return v
 if isinstance(v,list):return ''.join(flatten(x) for x in v)
 if isinstance(v,dict):return ''.join(flatten(x) for k,x in v.items() if k in ['text','extra','body','contents'])
 return ''
class Client:
 def __init__(self,name):
  self.s=socket.create_connection(('127.0.0.1',25597),5);self.s.settimeout(12);self.compressed=False;self.state='login';self.dialog=None
  self.send(0,vi(774)+st('localhost')+struct.pack('>H',25597)+vi(2));self.send(0,st(name)+uuid.uuid4().bytes)
 def read(self,n):
  data=b''
  while len(data)<n:
   part=self.s.recv(n-len(data))
   if not part:raise EOFError()
   data+=part
  return data
 def send(self,pid,payload=b''):
  raw=vi(pid)+payload
  if self.compressed:raw=vi(0)+raw
  self.s.sendall(vi(len(raw))+raw)
 def receive(self):
  raw=self.read(var(self.read))
  if self.compressed:
   b=Buf(raw);size=var(b.read);raw=zlib.decompress(b.b[b.pos:]) if size else b.b[b.pos:]
  b=Buf(raw);pid=var(b.read);payload=b.b[b.pos:]
  if self.state=='login':
   if pid==3:self.compressed=True
   elif pid==2:
    self.send(3);self.state='configuration'
    self.send(0,st('en_us')+bytes([2])+vi(0)+bytes([1,127])+vi(1)+bytes([0,1])+vi(0))
   elif pid==0:raise AssertionError('Login disconnect '+repr(payload)[:180])
  elif self.state=='configuration':
   if pid==14:self.send(7,payload)
   elif pid==3:self.send(3);self.state='play'
   elif pid==4:self.send(4,payload)
   elif pid==5:self.send(5,payload)
   elif pid==0:self.send(1,payload+bytes([0]))
   elif pid==2:raise AssertionError('Config disconnect '+repr(payload)[:180])
  else:
   if pid==0x2b:self.send(0x1b,payload)
   elif pid==0x46:self.send(0,vi(var(Buf(payload).read)))
   elif pid==0x74:self.send(0x0f);self.state='configuration'
   elif pid==0x8a:
    b=Buf(payload);assert var(b.read)==0;self.dialog=nbt(b)
   elif pid==0x20:raise AssertionError('Play disconnect '+repr(payload)[:180])
  # Packet diagnostics intentionally omit code/password values.
  return pid
def main():
 TEST.mkdir(parents=True);shutil.copy2(BASE/'server.jar',TEST/'server.jar');shutil.copy2(BASE/'velocity.toml',TEST/'velocity.toml')
 cfg=(ROOT/'src/main/resources/config.yml').read_text(encoding='utf-8').replace('\nenabled: false','\nenabled: true',1).replace('token: ""','token: "LOCAL_TEST_TOKEN"').replace('username: "CHANGE_ME_BOT"','username: "ArtificalTestBot"').replace('api-base-url: "https://api.telegram.org"','api-base-url: "http://127.0.0.1:18081"').replace('telegram-delay-ms: 1000','telegram-delay-ms: 0')
 plugin=TEST/'plugins/artificalauth';plugin.mkdir(parents=True,exist_ok=True);(plugin/'config.yml').write_text(cfg,encoding='utf-8')
 for name in ['ArtificalAuth-0.1.1-beta.jar','limboapi-1.1.27-SNAPSHOT.jar']:shutil.copy2(ROOT/'dist'/name,TEST/'plugins'/name)
 api=http.server.ThreadingHTTPServer(('127.0.0.1',18081),API);threading.Thread(target=api.serve_forever,daemon=True).start()
 log=open(TEST/'integration-console.log','w',encoding='utf-8')
 proc=subprocess.Popen([r'Z:\PrismLauncher\java\java-runtime-epsilon\bin\java.exe','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Xms128M','-Xmx768M','-jar','server.jar'],cwd=TEST,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
 try:
  deadline=time.monotonic()+35
  while time.monotonic()<deadline:
   data=(TEST/'integration-console.log').read_text(encoding='utf-8',errors='replace')
   if 'Авторизация полностью' in data:break
   if proc.poll() is not None:raise AssertionError(data[-1200:])
   time.sleep(.2)
  else:raise AssertionError('Startup failed '+data[-1000:])
  name='Test'+uuid.uuid4().hex[:8];cl=Client(name)
  for _ in range(150):
   cl.receive()
   if cl.dialog:break
  assert cl.dialog,'No dialog'
  body=flatten(cl.dialog);code=re.search(r'\b[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}\b',body)
  assert code,'No registration code'
  with guard:updates.append({'update_id':1,'message':{'message_id':1,'date':int(time.time()),'from':{'id':100,'is_bot':False},'chat':{'id':100,'type':'private'},'text':code.group()}})
  read_errors=[]
  def pump():
   try:
    while True:cl.receive()
   except Exception as e:read_errors.append(e)
  threading.Thread(target=pump,daemon=True).start()
  deadline=time.monotonic()+20
  while time.monotonic()<deadline:
   if any('управление аккаунтом' in m['text'] for m in sent):break
   time.sleep(.1)
  else:raise AssertionError('Binding failed')
  time.sleep(.5)
  a=json.loads((plugin/'accounts.json').read_text())['accounts'][name.lower()]
  assert a['delivered'] and a['ip']=='127.0.0.1'
  assert any('пароль: <code>' in m['text'] for m in sent)
  deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   data=(TEST/'integration-console.log').read_text(encoding='utf-8',errors='replace')
   if 'Unable to connect to main' in data or 'unable to connect to server main' in data:break
   time.sleep(.2)
  else:raise AssertionError('Authenticated player did not leave Limbo: '+data[-1200:])
  cl.s.close()
  time.sleep(.3)
  # Switch to password-only login using the same Telegram controls as a real user.
  count=len(sent)
  with guard:updates.append({'update_id':2,'message':{'message_id':2,'date':int(time.time()),'from':{'id':100,'is_bot':False},'chat':{'id':100,'type':'private'},'text':'/twofactor'}})
  deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   choices=[m for m in sent[count:] if 'Изменить настройку' in m.get('text','')]
   if choices:break
   time.sleep(.1)
  else:raise AssertionError('No settings confirmation')
  callback=choices[-1]['reply_markup']['inline_keyboard'][0][0]['callback_data']
  count=len(sent)
  with guard:updates.append({'update_id':3,'callback_query':{'id':'test-setting','from':{'id':100},'message':{'chat':{'id':100,'type':'private'}},'data':callback}})
  deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   if any('выключено' in m.get('text','') for m in sent[count:]):break
   time.sleep(.1)
  else:raise AssertionError('Settings not applied')
  second=Client(name)
  for _ in range(100):
   second.receive()
   if second.dialog:break
  assert second.dialog and second.dialog['inputs'][0]['key']=='password','No native password dialog'
  password_text=next(m['text'] for m in sent if 'пароль: <code>' in m.get('text',''))
  password=re.search(r'пароль: <code>(.*?)</code>',password_text).group(1)
  action=second.dialog['actions'][0]['action']['id']
  def utf(value):
   raw=value.encode();return struct.pack('>H',len(raw))+raw
  payload=bytes([10,8])+utf('password')+utf(password)+bytes([0])
  before=(TEST/'integration-console.log').read_text(encoding='utf-8',errors='replace').count('unable to connect to server main')
  # Keep the native password screen open past Velocity's previous 30s idle timeout.
  wait_until=time.monotonic()+35
  while time.monotonic()<wait_until:second.receive()
  second.send(0x41,st(action)+bytes([1])+payload)
  deadline=time.monotonic()+12
  while time.monotonic()<deadline:
   try:second.receive()
   except (AssertionError,EOFError):break
  time.sleep(.3)
  data=(TEST/'integration-console.log').read_text(encoding='utf-8',errors='replace')
  assert data.count('unable to connect to server main')>before,'Password reply did not authorize transfer'
  second.s.close()
  print('PASS: real Java 1.21.11 registration and password dialogs; local Telegram binding/settings; native password response; 35s idle without timeout; transfer out of Limbo.',flush=True)
 finally:
  try:proc.stdin.write(b'end\n');proc.stdin.flush();proc.wait(timeout=20)
  except Exception:proc.terminate();proc.wait(timeout=5)
  api.shutdown();log.close()
if __name__=='__main__':main()
