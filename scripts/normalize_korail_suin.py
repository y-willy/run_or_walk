from zipfile import ZipFile
from pathlib import Path
from decimal import Decimal, ROUND_HALF_UP
import xml.etree.ElementTree as E
import csv,json,hashlib,collections
import argparse
parser=argparse.ArgumentParser(description='Normalize the official 2026-08-22 Suin-Bundang workbook without treating passing times as stops.')
parser.add_argument('--xlsx', required=True, type=Path)
parser.add_argument('--output', required=True, type=Path)
args=parser.parse_args()
BASE=args.output
BASE.mkdir(parents=True, exist_ok=True)
SOURCE=args.xlsx
N={'m':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
NSREL='{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'
def ftime(sec):
    if sec is None:return ''
    return f'{sec//3600:02d}:{sec//60%60:02d}:{sec%60:02d}'
def nsec(v):
    try:return int((Decimal(v)*86400).quantize(Decimal(1),rounding=ROUND_HALF_UP))
    except:return None
rows=[];passes=[];sheets=[];unmatched=[]
with ZipFile(SOURCE) as z:
    ss=[''.join(si.itertext()) for si in E.fromstring(z.read('xl/sharedStrings.xml')).findall('m:si',N)]
    relationships={r.attrib['Id']:r.attrib['Target'] for r in E.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    for sh in E.fromstring(z.read('xl/workbook.xml')).findall('m:sheets/m:sheet',N):
        name=sh.attrib['name'];target=relationships[sh.attrib[NSREL]];path='xl/'+target
        cells={c.attrib['r']:(ss[int(c.find('m:v',N).text)] if c.attrib.get('t')=='s' else c.find('m:v',N).text) for c in E.fromstring(z.read(path)).findall('.//m:c',N) if c.find('m:v',N) is not None}
        trains={k[:-1]:v for k,v in cells.items() if k.endswith('4') and k[:-1].isalpha() and k!='A4'}
        count=collections.Counter()
        for col,train in trains.items():
            origin=cells.get(col+'2','').strip();dest=cells.get(col+'3','').strip();last=None;offset=0
            for row in range(5,130,2):
                station=cells.get('A'+str(row),'').strip()
                if not station:continue
                raw_arr=cells.get(col+str(row),'');raw_dep=cells.get(col+str(row+1),'')
                arr=nsec(raw_arr);dep=nsec(raw_dep)
                for variable,sec in [('arr',arr),('dep',dep)]:
                    if sec is None:continue
                    sec+=offset
                    if last is not None and sec<last:
                        offset+=86400;sec+=86400
                    last=sec
                    if variable=='arr':arr=sec
                    else:dep=sec
                if arr is None and dep is None:continue
                record={'LINE':'수인분당','SI_ID':'','STATION_NM':station,'WEEKTAG':'DAY' if name.startswith('평일') else 'HOLIDAY','INOUTTAG':'UP' if name.endswith('상행') else 'DOWN','GUBHANG':'','TRAIN_NO':train,'STT':ftime(arr),'EDT':ftime(dep),'ST_STT_NM':origin,'ED_STT_NM':dest,'SOURCE_SHEET':name,'SOURCE_ARR_CELL':col+str(row),'SOURCE_DEP_CELL':col+str(row+1),'SOURCE_RAW_ARR':raw_arr,'SOURCE_RAW_DEP':raw_dep}
                if arr is None and station!=origin:
                    passes.append(record);count['passing']+=1;continue
                rows.append(record);count['stops']+=1
        sheets.append({'sheet':name,'worksheetPath':path,'trains':len(trains),'counts':dict(count)})
header=['LINE','SI_ID','STATION_NM','WEEKTAG','INOUTTAG','GUBHANG','TRAIN_NO','STT','EDT','ST_STT_NM','ED_STT_NM','SOURCE_SHEET','SOURCE_ARR_CELL','SOURCE_DEP_CELL','SOURCE_RAW_ARR','SOURCE_RAW_DEP']
for filename,data in [('korail_suin_bundang_20260822_stops.csv',rows),('korail_suin_bundang_20260822_passes.csv',passes)]:
    with (BASE/filename).open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f,fieldnames=header);w.writeheader();w.writerows(data)
manifest={'provider':'한국철도공사(KORAIL)','noticeUrl':'https://www.korail.com/ticket/guest/notice/25642','noticeRegisteredAt':'2026-08-18','effectiveFrom':'2026-08-22','downloadUrl':'https://www.korail.com/file/cubedata/COMMON/jfile/202608/21/202608211a023449d0b840.xlsx','sourceFilename':'05. 수인분당선 열차시각표(2026년8월22일부터).xlsx','sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'bytes':SOURCE.stat().st_size,'retrievedDate':'2026-10-25','stationNamesRaw':True,'dayCategories':{'DAY':'평일','HOLIDAY':'휴일; workbook does not explicitly define Saturdays'},'expressCategory':'not explicitly present, GUBHANG left empty','sheets':sheets,'stopRows':len(rows),'passingRowsExcluded':len(passes),'licenseCatalogUrl':'https://www.data.go.kr/data/15052169/fileData.do','licenseCatalogText':'이용허락범위 제한 없음; catalog updated 2025-09-02 and points to current Korail timetable website'}
(BASE/'korail_suin_bundang_20260822_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(manifest,ensure_ascii=False,indent=2))
