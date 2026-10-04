package com.smartcloset.batch;

import com.smartcloset.image.*;
import com.smartcloset.recognition.Recognition.Bounds;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javax.imageio.ImageIO;

public final class BatchImages {
    private BatchImages() {}
    public static byte[] png(BufferedImage image) throws IOException { var out = new ByteArrayOutputStream(); ImageIO.write(image,"png",out); return out.toByteArray(); }
    public static BufferedImage read(byte[] bytes) { return Images.decodePreservingAlpha(bytes,12000000).image(); }
    public static BufferedImage crop(byte[] original, Bounds bounds, int side) {
        var image = Images.sampled(original, side * 2).image();
        int padding = Math.max(2, (int)(Math.max(image.getWidth(), image.getHeight()) * .025));
        int x = Math.max(0, (int)Math.floor(bounds.x()*image.getWidth())-padding);
        int y = Math.max(0, (int)Math.floor(bounds.y()*image.getHeight())-padding);
        int right = Math.min(image.getWidth(), (int)Math.ceil((bounds.x()+bounds.width())*image.getWidth())+padding);
        int bottom = Math.min(image.getHeight(), (int)Math.ceil((bounds.y()+bounds.height())*image.getHeight())+padding);
        return image.getSubimage(x,y,Math.max(1,right-x),Math.max(1,bottom-y));
    }
    public record Grid(int columns,int rows) {}
    public static Grid grid(int count) {
        if(count<1 || count>10) throw ProcessingException.invalid("整理图需要1到10个有效格位。");
        // Preserve historical 1–3 item layouts; larger new groups use a compact grid.
        int columns=count<=3?count:(int)Math.ceil(Math.sqrt(count));
        return new Grid(columns,(count+columns-1)/columns);
    }
    /** Numbered input and split output share the same row-major grid. */
    public static BufferedImage reference(List<BufferedImage> inputs, int side) {
        var grid=grid(inputs.size());
        if(inputs.size()>3) side=Math.min(side,1600/Math.max(grid.columns(),grid.rows()));
        var atlas = new BufferedImage(side * grid.columns(), side * grid.rows(), BufferedImage.TYPE_INT_RGB);
        var g = atlas.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0,0,atlas.getWidth(),atlas.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for(int i=0;i<inputs.size();i++) {
                int left=(i%grid.columns())*side,top=(i/grid.columns())*side;
                var image = inputs.get(i); double scale = Math.min((side-48d)/image.getWidth(),(side-64d)/image.getHeight());
                int w=(int)Math.round(image.getWidth()*scale),h=(int)Math.round(image.getHeight()*scale);
                g.drawImage(image,left+(side-w)/2,top+32+(side-48-h)/2,w,h,null);
                g.setColor(Color.DARK_GRAY);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,22));g.drawString("#"+(i+1),left+12,top+25);
            }
        } finally { g.dispose(); }
        return atlas;
    }
    public static List<byte[]> split(byte[] bytes, int count, String expectedSize) throws IOException {
        var grid=grid(count);
        var image = read(bytes); var dims=expectedSize.split("x");
        int w=Integer.parseInt(dims[0]),h=Integer.parseInt(dims[1]);
        // A single garment has no grid boundary. Preserve the provider's native pixels.
        if(count==1) return List.of(png(image));
        // Providers may return a uniformly scaled image. Slot identity depends on
        // proportions, not on matching the requested pixel count exactly.
        double ratio=(double)image.getWidth()/image.getHeight(),expectedRatio=(double)w/h;
        double cellRatio=(double)grid.columns()/grid.rows();
        if(w<=0 || h<=0 || (Math.abs(ratio/expectedRatio-1)>.01 && Math.abs(ratio/cellRatio-1)>.01) || image.getWidth()<grid.columns() || image.getHeight()<grid.rows())
            throw ProcessingException.invalid("生成图比例与多件格位不一致，整图已保留，请核对后再处理。");
        var results = new java.util.ArrayList<byte[]>();
        for(int i=0;i<count;i++) {
            int col=i%grid.columns(),row=i/grid.columns();
            int left=col*image.getWidth()/grid.columns(),right=(col+1)*image.getWidth()/grid.columns();
            int top=row*image.getHeight()/grid.rows(),bottom=(row+1)*image.getHeight()/grid.rows();
            results.add(png(image.getSubimage(left,top,right-left,bottom-top)));
        }
        return results;
    }
}
