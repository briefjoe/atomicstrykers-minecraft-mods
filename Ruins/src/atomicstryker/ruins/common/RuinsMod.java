package atomicstryker.ruins.common;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderEnd;
import net.minecraft.world.WorldProviderHell;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.entity.player.PlayerEvent.BreakSpeed;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.IWorldGenerator;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;

@Mod(modid = "AS_Ruins", name = "Ruins Mod", version = "10.4", dependencies = "after:ExtraBiomes")
public class RuinsMod
{
    public final static int FILE_TEMPLATE = 0, FILE_COMPLEX = 1;
    public final static String TEMPLATE_EXT = "tml", COMPLEX_EXT = "cml";
    public final static int DIR_NORTH = 0, DIR_EAST = 1, DIR_SOUTH = 2, DIR_WEST = 3;
    public static final int BIOME_NONE = 500;

    private ConcurrentHashMap<Integer, WorldHandle> generatorMap;
    private ConcurrentLinkedQueue<int[]> currentlyGenerating;

    @EventHandler
    public void load(FMLInitializationEvent evt)
    {
        generatorMap = new ConcurrentHashMap<Integer, WorldHandle>();
        currentlyGenerating = new ConcurrentLinkedQueue<int[]>();
        GameRegistry.registerWorldGenerator(new RuinsWorldGenerator());
        MinecraftForge.EVENT_BUS.register(this);
    }
    
    private long nextInfoTime;
    @ForgeSubscribe
    public void onBreakSpeed(BreakSpeed event)
    {
        ItemStack is = event.entityPlayer.getCurrentEquippedItem();
        if (is != null && is.itemID == Item.stick.itemID && System.currentTimeMillis() > nextInfoTime)
        {
            nextInfoTime = System.currentTimeMillis() + 1000l;
            event.entityPlayer.addChatMessage(String.format("BlockName [%s], blockID [%d], metadata [%d]", event.block.getUnlocalizedName(), event.block.blockID, event.metadata));
        }
    }
    
    public class RuinsWorldGenerator implements IWorldGenerator
    {
        @Override
        public void generate(Random random, int chunkX, int chunkZ, World world, IChunkProvider chunkGenerator, IChunkProvider chunkProvider)
        {
            if (Math.abs(chunkX) < 3 && Math.abs(chunkZ) < 3)
            {
                return; // the 0,0 bug is really annoying. SLEDGEHAMMER FIX!
            }
            
            if (world.isRemote)
            {
                return;
            }
            
            int[] tuple = { chunkX, chunkZ };
            if (currentlyGenerating.contains(tuple))
            {
                System.out.printf("Ruins Mod caught recursive generator call at chunk [%d|%d]", chunkX, chunkZ);
            }
            else
            {
                currentlyGenerating.add(tuple);
                if (world.provider instanceof WorldProviderHell)
                {
                    generateNether(world, random, chunkX*16, chunkZ*16);
                }
                else if (world.provider instanceof WorldProviderEnd)
                {
                    generateSurface(world, random, chunkX*16, chunkZ*16);
                }
                else // normal world
                {
                    generateSurface(world, random, chunkX*16, chunkZ*16);
                }
                currentlyGenerating.remove(tuple);
            }
        }
    }

    private void generateNether(World world, Random random, int chunkX, int chunkZ)
    {
        WorldHandle wh = getWorldHandle(world);
        if (wh.ruins != null && wh.ruins.loaded)
        {
            wh.generator.generateNether(world, random, chunkX, 0, chunkZ);
        }
    }

    private void generateSurface(World world, Random random, int chunkX, int chunkZ)
    {
        WorldHandle wh = getWorldHandle(world);
        if (wh.ruins != null && wh.ruins.loaded)
        {
            wh.generator.generateNormal(world, random, chunkX, 0, chunkZ);
        }
    }
    
    private class WorldHandle
    {        
        RuinHandler ruins;
        RuinGenerator generator;
    }
    
    private WorldHandle getWorldHandle(World world)
    {
        WorldHandle wh = null;
        if (!generatorMap.containsKey(world.getWorldInfo().getDimension()))
        {
            wh = new WorldHandle();
            createHandler(wh, world);
            generatorMap.put(world.getWorldInfo().getDimension(), wh);
        }
        else
        {
            wh = generatorMap.get(world.getWorldInfo().getDimension());
        }
        
        return wh;
    }

    public static File getWorldSaveDir(World world)
    {
        ISaveHandler worldsaver = world.getSaveHandler();
        
        if (worldsaver.getChunkLoader(world.provider) instanceof AnvilChunkLoader)
        {
            AnvilChunkLoader loader = (AnvilChunkLoader) worldsaver.getChunkLoader(world.provider);
            
            for (Field f : loader.getClass().getDeclaredFields())
            {
                if (f.getType().equals(File.class))
                {
                    try
                    {
                        f.setAccessible(true);
                        File saveLoc = (File) f.get(loader);
                        System.out.println("Ruins mod determines World Save Dir to be at: "+saveLoc);
                        return saveLoc;
                    }
                    catch (Exception e)
                    {
                        System.out.println("Ruins mod failed trying to find World Save dir:");
                        e.printStackTrace();
                    }
                }
            }
        }
        
        return null;
    }

    public static int getBiomeFromName(String name)
    {
        for (int i = 0; i < BiomeGenBase.biomeList.length; i++)
        {
            if (BiomeGenBase.biomeList[i] != null && BiomeGenBase.biomeList[i].biomeName.equalsIgnoreCase(name))
            {
                return BiomeGenBase.biomeList[i].biomeID;
            }
        }

        return -1;
    }

    public static File getMinecraftBaseDir()
    {
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT)
        {
            return FMLClientHandler.instance().getClient().mcDataDir;
        }
            
        return FMLCommonHandler.instance().getMinecraftServerInstance().getFile("");
    }

    private void createHandler(WorldHandle worldHandle, World world)
    {
        // load in defaults
        try
        {
            File worlddir = getWorldSaveDir(world);
            worldHandle.ruins = new RuinHandler(worlddir);
            worldHandle.generator = new RuinGenerator(worldHandle.ruins);
        }
        catch (Exception e)
        {
            System.err.println("There was a problem loading the ruins mod:");
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
    }

    public static void copyGlobalOptionsTo(File dir) throws Exception
    {
        File copyfile = new File(dir, "ruins.txt");
        if (copyfile.exists())
        {
            return;
        }
        File basedir = getMinecraftBaseDir();
        basedir = new File(basedir, "mods");
        File basefile = new File(basedir, "ruins.txt");
        if (!basefile.exists())
        {
            createDefaultGlobalOptions(basedir);
        }
        FileInputStream fis = new FileInputStream(basefile);
        FileOutputStream fos = new FileOutputStream(copyfile);
        FileChannel in = fis.getChannel();
        FileChannel out = fos.getChannel();
        try
        {
            in.transferTo(0, in.size(), out);
        }
        catch (Exception e)
        {
            throw e;
        }
        finally
        {
            if (in != null)
            {
                in.close();
                fis.close();
            }
            if (out != null)
            {
                out.close();
                fos.close();
            }
        }
    }

    private static void createDefaultGlobalOptions(File dir) throws Exception
    {
        File file = new File(dir, "ruins.txt");
        PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(file)));
        pw.println("# Global Options for the Ruins mod");
        pw.println("#");
        pw.println("# tries_per_chunk is the number of times, per chunk, that the generator will");
        pw.println("#     attempt to create a ruin.");
        pw.println("#");
        pw.println("# chance_to_spawn is the chance, out of 100, that a ruin will be generated per");
        pw.println("#     try in this chunk.  This may still fail if the ruin does not have a");
        pw.println("#     suitable place to generate.");
        pw.println("#");
        pw.println("# chance_for_site is the chance, out of 100, that another ruin will attempt to");
        pw.println("#     spawn nearby if a ruin was already successfully spawned.  This bypasses");
        pw.println("#     the normal tries per chunk, so if this chance is set high you may end up");
        pw.println("#     with a lot of ruins even with a low tries per chunk and chance to spawn.");
        pw.println("#");
        pw.println("# specific_<biome name> is the chance, out of 100, that a ruin spawning in the");
        pw.println("#     specified biome will be chosen from the biome specific folder.  If not,");
        pw.println("#     it will choose a generic ruin from the root ruin folder.");
        pw.println();
        pw.println("tries_per_chunk_normal=6");
        pw.println("chance_to_spawn_normal=10");
        pw.println("chance_for_site_normal=15");
        pw.println();
        pw.println("tries_per_chunk_nether=6");
        pw.println("chance_to_spawn_nether=10");
        pw.println("chance_for_site_nether=15");
        pw.println("disableRuinSpawnCoordsLogging=true");
        pw.println();
        // print all the biomes!
        for (int i = 0; i < BiomeGenBase.biomeList.length; i++)
        {
            if (BiomeGenBase.biomeList[i] != null)
            {
                pw.println("specific_" + BiomeGenBase.biomeList[i].biomeName + "=75");
            }
        }
        pw.flush();
        pw.close();
    }
}