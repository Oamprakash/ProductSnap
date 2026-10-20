from PIL import Image, ImageDraw
INK=(17,18,20); PAPER=(244,241,234); ACC=(255,91,46)
def mark(size, crop, mono=None):
    """Draw the 108-unit canvas, showing units [54-crop/2, 54+crop/2] across `size` px (supersampled)."""
    S=size*4; k=S/crop; o=54-crop/2
    P=lambda x,y:((x-o)*k,(y-o)*k)
    im=Image.new("RGB",(S,S),INK if mono is None else (40,44,52)); d=ImageDraw.Draw(im)
    pc=PAPER if mono is None else mono; ac=ACC if mono is None else mono
    w=3.5*k
    bgc=INK if mono is None else (40,44,52)
    # rounded square outline (centre line 33..75, bend radius 4), then cut the middle of each edge
    a=P(33,33); b=P(75,75); h=w/2
    d.rounded_rectangle([a[0]-h,a[1]-h,b[0]+h,b[1]+h],radius=(4+1.75)*k,outline=pc,width=int(round(w)))
    for (x0,y0,x1,y1) in [(42,30,66,36),(42,72,66,78),(30,42,36,66),(72,42,78,66)]:
        p0=P(x0,y0); p1=P(x1,y1); d.rectangle([p0[0],p0[1],p1[0],p1[1]],fill=bgc)
    for (x,y) in [(42,33),(66,33),(42,75),(66,75),(33,42),(33,66),(75,42),(75,66)]:
        cx,cy=P(x,y); d.ellipse([cx-h,cy-h,cx+h,cy+h],fill=pc)
    x,y=P(54,54); r1=(12+2.25)*k; r0=(12-2.25)*k
    d.ellipse([x-r1,y-r1,x+r1,y+r1],fill=ac); d.ellipse([x-r0,y-r0,x+r0,y+r0],fill=INK if mono is None else (40,44,52))
    r=4*k; d.ellipse([x-r,y-r,x+r,y+r],fill=ac)
    return im.resize((size,size),Image.LANCZOS)
# Play Store icon: full square 512, content scaled up (show central 76 units)
mark(512,76).save("productsnap-play-icon-512.png")
# Preview: launcher masks at 72dp-ish (the 72/108 visible area), circle + squircle + themed
vis=mark(216,72)
def masked(img,shape):
    m=Image.new("L",img.size,0); dd=ImageDraw.Draw(m); s=img.size[0]
    if shape=="circle": dd.ellipse([0,0,s-1,s-1],fill=255)
    else: dd.rounded_rectangle([0,0,s-1,s-1],radius=int(s*0.3),fill=255)
    out=Image.new("RGBA",img.size,(0,0,0,0)); out.paste(img,(0,0),m); return out
themed=mark(216,72,mono=(212,227,255))
bg=Image.new("RGBA",(216*3+4*40,216+80),(232,234,238,255))
for i,(im,sh) in enumerate([(vis,"circle"),(vis,"squircle"),(themed,"circle")]):
    t=masked(im,sh); bg.paste(t,(40+i*(216+40),40),t)
bg.save("icon-preview.png")
