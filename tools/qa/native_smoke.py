# Legacy uiautomator dump suppresses accessibility services while attached.
# This script covers appearance/navigation only; use a non-suppressing observer for live service tests.
from pathlib import Path
import subprocess,xml.etree.ElementTree as ET,re,json,time,argparse,shutil
parser=argparse.ArgumentParser(description='Native smoke checks on an explicitly selected test Android device. Changes its rotation/night-mode settings and restarts only the assistant app.')
parser.add_argument('--serial',required=True)
parser.add_argument('--adb',default=shutil.which('adb'))
parser.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[2]/'docs/development/build-evidence')
args=parser.parse_args()
if not args.adb:parser.error('Provide --adb or add Android platform-tools to PATH')
adb=args.adb;serial=args.serial;out=args.output;out.mkdir(parents=True,exist_ok=True);checks=[]
def cmd(*args):
 p=subprocess.run([adb,'-s',serial,*args],capture_output=True)
 if p.returncode:raise RuntimeError(p.stderr.decode(errors='replace'))
 return p.stdout
def shell(*args):return cmd('shell',*args).decode(errors='replace')
def tree():
 shell('uiautomator','dump','/sdcard/yys-window.xml')
 return ET.fromstring(cmd('exec-out','cat','/sdcard/yys-window.xml'))
def find(text,root=None):
 root=tree() if root is None else root
 return [e for e in root.iter('node') if e.get('text')==text or e.get('content-desc')==text]
def wait(text):
 for _ in range(8):
  t=tree()
  if find(text,t):return t
  time.sleep(.2)
 raise AssertionError('Missing '+text)
def click(text):
 t=wait(text);n=find(text,t)[-1];v=list(map(int,re.findall(r'\d+',n.get('bounds'))));shell('input','tap',str((v[0]+v[2])//2),str((v[1]+v[3])//2));time.sleep(.25)
def selected(text):
 t=tree();parents={c:p for p in t.iter() for c in p};e=find(text,t)[-1]
 while e is not None:
  if e.get('selected')=='true' or e.get('checked')=='true':return True
  e=parents.get(e)
 return False
def bars(dark):
 text=shell('dumpsys','window','windows')
 block=next(b for b in re.split(r'(?=  Window #)',text) if 'io.github.yysassist/io.github.yysassist.MainActivity' in b.split('\n',1)[0])
 found=re.search(r'^\s*apr=(.*)',block,re.M);flags=found.group(1).strip() if found else ''
 assert ('LIGHT_STATUS_BARS' in flags) == (not dark),flags
 assert ('LIGHT_NAVIGATION_BARS' in flags) == (not dark),flags
 return flags

def shot(name):
 data=cmd('exec-out','screencap','-p');assert data[:8]==b'\x89PNG\r\n\x1a\n';(out/(name+'.png')).write_bytes(data)
try:
 wait('队列为空');click('设置');click('系统');shell('cmd','uimode','night','no');click('工作台');wait('队列为空');checks.append('cold launch and true empty queue');shot('home-light-portrait')
 click('设置');wait('系统');click('深色');assert selected('深色');bars(True);shot('settings-dark-portrait');checks.append('manual dark selection')
 shell('am','force-stop','io.github.yysassist');shell('am','start','-W','-n','io.github.yysassist/.MainActivity');click('设置');assert selected('深色');checks.append('dark preference survives process stop and restart')
 shell('settings','put','system','accelerometer_rotation','0');shell('settings','put','system','user_rotation','1');time.sleep(1);wait('系统');assert selected('深色');shot('settings-dark-landscape');checks.append('settings route and preference survive rotation')
 click('浅色');assert selected('浅色');bars(False);shot('settings-light-landscape');checks.append('manual light in landscape')
 shell('cmd','uimode','night','yes');time.sleep(.4);assert selected('浅色');bars(False);checks.append('manual mode remains selected after system dark change')
 click('系统');assert selected('系统');bars(True);shot('settings-system-dark-landscape');shell('cmd','uimode','night','no');time.sleep(.5);assert selected('系统');bars(False);shot('settings-system-light-landscape');checks.append('system preference and actual system bars follow both system changes')
 click('历史记录');wait('通知');click('运行');wait('暂无运行记录');shot('history-landscape');checks.append('landscape history tabs reachable')
 shell('settings','put','system','user_rotation','0');time.sleep(.5);wait('暂无运行记录');shot('history-portrait');checks.append('history selection survives portrait return')
 click('设置');click('运行环境');wait('无障碍服务');shot('environment-permission-view');checks.append('permission view reachable; service bridge checked separately without UIAutomator')
 (out/'native-smoke.json').write_text(json.dumps({'scope':'Isolated local Android API36 ARM64 emulator; actual APK UI and DataStore. No game or task execution. Screenshot appearance is also visually reviewed, selection assertions alone do not prove contrast.','checks':checks,'success':True},ensure_ascii=False,indent=2));print(json.dumps({'checks':checks,'success':True},ensure_ascii=False))
except Exception as e:
 shot('native-smoke-failure');(out/'native-smoke.json').write_text(json.dumps({'checks':checks,'success':False,'error':str(e)},ensure_ascii=False,indent=2));raise
