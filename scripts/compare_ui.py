from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]/'docs/ui-qa'
for name in ['teacher-home','teacher-room','timetable','student-home','student-room','counselor-home','analytics']:
    a=ROOT/('reference-'+name+'.jpg');b=ROOT/('after-'+name+'.jpg')
    if not(a.exists() and b.exists()):continue
    left,right=Image.open(a),Image.open(b)
    assert left.size==right.size, f'{name}: capture sizes differ: {left.size} vs {right.size}'
    canvas=Image.new('RGB',(left.width*2,left.height),'white');canvas.paste(left,(0,0));canvas.paste(right,(left.width,0));canvas.save(ROOT/(name+'-comparison.jpg'),quality=95)
    if name=='teacher-home':
        # A 1:1 focused crop keeps the shared hero, typography and card rhythm readable.
        canvas.crop((left.width//6,55,left.width*2,450)).save(ROOT/'teacher-home-detail.jpg',quality=95)
    print(name,left.size,'comparison saved')
