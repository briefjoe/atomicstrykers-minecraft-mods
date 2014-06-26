package atomicstryker.ruins.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListSet;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.MinecraftForge;

public class RuinGenerator
{

    private final static String fileName = "RuinsPositionsFile.txt";

    private final RuinHandler ruinsHandler;
    private final RuinStats stats;
    private int numTries = 0, LastNumTries = 0;
    private final int WORLD_MAX_HEIGHT = 256;
    private final ConcurrentSkipListSet<RuinData> registeredRuins;
    private File ruinsDataFile;
    private File ruinsDataFileWriting;
    private final RuinData spawnPointBlock;

    public RuinGenerator(RuinHandler rh, World world)
    {
        ruinsHandler = rh;
        stats = new RuinStats();
        registeredRuins = new ConcurrentSkipListSet<RuinData>();
        
        // lets create a banned area 2 chunks around the spawn
        final int minX = world.getSpawnPoint().posX - 32;
        final int minY = world.getSpawnPoint().posY - 32;
        final int minZ = world.getSpawnPoint().posZ - 32;
        spawnPointBlock = new RuinData(minX, minX+64, minY, minY+64, minZ, minZ+64, "SpawnPointBlock");
        
        ruinsDataFile = new File(rh.saveFolder, fileName);
        ruinsDataFileWriting = new File(rh.saveFolder, fileName+"_writing");

        if (ruinsDataFile.getAbsolutePath().contains(world.getWorldInfo().getWorldName()))
        {
            new LoadThread().start();
        }
        else
        {
            System.err.println("Ruins attempted to load invalid worldname " + world.getWorldInfo().getWorldName() + " posfile");
        }
    }

    private class LoadThread extends Thread
    {
        @Override
        public void run()
        {
            loadPosFile(ruinsDataFile);
        }
    }

    public void flushPosFile(String worldName)
    {
        if (registeredRuins.isEmpty() || worldName.equals("MpServer"))
        {
            return;
        }

        new FlushThread().start();
    }

    private class FlushThread extends Thread
    {
        @Override
        public void run()
        {
            if (ruinsDataFileWriting.exists())
            {
                ruinsDataFileWriting.delete();
            }

            try
            {
                PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(ruinsDataFileWriting)));
                pw.println("# Ruins data management file. Below, you see all data accumulated by AtomicStrykers Ruins during the last run of this World.");
                pw.println("# Data is noted as follows: Each line stands for one successfull Ruin spawn. Data syntax is:");
                pw.println("# xMin yMin zMin xMax yMax zMax templateName");
                pw.println("# everything but the last value is an integer value. Template name equals the template file name.");
                pw.println("#");
                pw.println("# DO NOT EDIT THIS FILE UNLESS YOU ARE SURE OF WHAT YOU ARE DOING");
                pw.println("#");
                pw.println("# The primary function of this file is to lock areas you do not want Ruins spawning in. Put them here before worldgen.");
                pw.println("# It should also prevent Ruins re-spawning under any circumstances. Areas registered in here block any overlapping new Ruins.");
                pw.println("# Empty lines and those prefixed by '#' are ignored by the parser. Don't save notes in here, file gets wiped upon flushing.");
                pw.println("#");
                for (RuinData r : registeredRuins)
                {
                    pw.println(r.toString());
                    // System.out.println("saved ruin data line ["+r.toString()+"]");
                }

                pw.flush();
                pw.close();
                // System.out.println("Ruins Positions flushed, entries "+registeredRuins.size());
                
                if (ruinsDataFile.exists())
                {
                    ruinsDataFile.delete();
                }
                ruinsDataFileWriting.renameTo(ruinsDataFile);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    private void loadPosFile(File file)
    {
        try
        {
            if (!file.exists())
            {
                file.createNewFile();
                
                // put it into the initial set
                registeredRuins.add(spawnPointBlock);
            }
            int lineNumber = 1;
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line = br.readLine();
            while (line != null)
            {
                line = line.trim();
                if (!line.startsWith("#") && !line.isEmpty())
                {
                    try
                    {
                        registeredRuins.add(new RuinData(line));
                    }
                    catch (Exception e)
                    {
                        System.err.println("Ruins positions file is invalid in line " + lineNumber + ", skipping...");
                    }
                }

                lineNumber++;
                line = br.readLine();
            }
            br.close();
            // System.out.println("Ruins Positions reloaded. Lines "+lineNumber+", entries "+registeredRuins.size());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public boolean generateNormal(World world, Random random, int xBase, int j, int zBase)
    {
        for (int c = 0; c < ruinsHandler.triesPerChunkNormal; c++)
        {
            if (random.nextFloat() * 100 < ruinsHandler.chanceToSpawnNormal)
            {
                createBuilding(world, random, xBase + random.nextInt(16), zBase + random.nextInt(16), 0, false);
            }
        }
        return true;
    }

    public boolean generateNether(World world, Random random, int xBase, int j, int zBase)
    {
        for (int c = 0; c < ruinsHandler.triesPerChunkNether; c++)
        {
            if (random.nextFloat() * 100 < ruinsHandler.chanceToSpawnNether)
            {
                int xMod = (random.nextBoolean() ? random.nextInt(16) : 0 - random.nextInt(16));
                int zMod = (random.nextBoolean() ? random.nextInt(16) : 0 - random.nextInt(16));
                createBuilding(world, random, xBase + xMod, zBase + zMod, 0, true);
            }
        }
        return true;
    }

    private void createBuilding(World world, Random random, int x, int z, int minDistance, boolean nether)
    {        
        final int rotate = random.nextInt(4);
        final BiomeGenBase biome = world.getBiomeGenForCoordsBody(x, z);
        int biomeID = biome.biomeID;

        if (ruinsHandler.useGeneric(random, biomeID))
        {
            biomeID = RuinsMod.BIOME_NONE;
        }
        stats.biomes[biomeID]++;
        
        RuinTemplate ruinTemplate = ruinsHandler.getTemplate(random, biomeID);
        if (ruinTemplate == null)
        {
            biomeID = RuinsMod.BIOME_NONE;
            ruinTemplate = ruinsHandler.getTemplate(random, biomeID);

            if (ruinTemplate == null)
            {
                return;
            }
        }
        numTries++;

        if (minDistance != 0)
        {
            stats.siteTries++;
            // tweak the x and z from the Min Distance, minding the bounding box
            minDistance += random.nextInt(3) + ruinTemplate.getMinDistance();
            x += (random.nextInt(2) == 1 ? 0 - minDistance : minDistance);
            z += (random.nextInt(2) == 1 ? 0 - minDistance : minDistance);
        }
        
        int y = findSuitableY(world, ruinTemplate, x, z, nether);
        if (y > 0)
        {
            if (willOverlap(ruinTemplate, x, y, z, rotate))
            {
                // try again.
                int xTemp = getRandomAdjustment(random, x, minDistance);
                int zTemp = getRandomAdjustment(random, z, minDistance);
                if (willOverlap(ruinTemplate, xTemp, y, zTemp, rotate))
                {
                    // last chance
                    xTemp = getRandomAdjustment(random, x, minDistance);
                    zTemp = getRandomAdjustment(random, z, minDistance);
                    if (willOverlap(ruinTemplate, xTemp, y, zTemp, rotate))
                    {
                        stats.BoundingBoxFails++;
                        // System.out.println("Bounding Box fail "+stats.BoundingBoxFails);
                        return;
                    }
                    x = xTemp;
                    z = zTemp;
                }
                else
                {
                    x = xTemp;
                    z = zTemp;
                }
            }

            if (checkMinDistance(ruinTemplate.getRuinData(x, y, z, rotate)))
            {
                y = ruinTemplate.checkArea(world, x, y, z, rotate);
                if (y < 0)
                {
                    stats.LevelingFails++;
                    // System.out.println("checkArea fail");
                    return;
                }
                
                if (MinecraftForge.EVENT_BUS.post(new EventRuinTemplateSpawn(world, ruinTemplate, x, y, z, rotate, false)))
                {
                    return;
                }
                
                if (!ruinsHandler.disableLogging)
                {
                    if (minDistance != 0)
                    {
                        System.out.printf("Creating ruin %s of Biome %s as part of a site at [%d|%d|%d]\n", ruinTemplate.getName(), biome.biomeName,
                                x, y, z);
                    }
                    else
                    {
                        System.out.printf("Creating ruin %s of Biome %s at [%d|%d|%d]\n", ruinTemplate.getName(), biome.biomeName, x, y, z);
                    }
                }
                stats.NumCreated++;

                ruinTemplate.doBuild(world, random, x, y, z, rotate);
                registeredRuins.add(ruinTemplate.getRuinData(x, y, z, rotate));
            }
            else
            {
                // System.out.println("Min Dist fail");
                stats.BoundingBoxFails++;
                return;
            }
        }
        else
        {
            // System.out.println("y fail");
            stats.LevelingFails++;
        }

        if (numTries > (LastNumTries + 1000))
        {
            LastNumTries = numTries;
            printStats();
        }
    }

    private void printStats()
    {
        if (!ruinsHandler.disableLogging)
        {
            int total =
                    stats.NumCreated + stats.BadBlockFails + stats.LevelingFails + stats.CutInFails + stats.OverhangFails + stats.NoAirAboveFails
                            + stats.BoundingBoxFails;
            System.out.println("Current Stats:");
            System.out.println("    Total Tries:                 " + total);
            System.out.println("    Number Created:              " + stats.NumCreated);
            System.out.println("    Site Tries:                  " + stats.siteTries);
            System.out.println("    Within Another Bounding Box: " + stats.BoundingBoxFails);
            System.out.println("    Bad Blocks:                  " + stats.BadBlockFails);
            System.out.println("    No Leveling:                 " + stats.LevelingFails);
            System.out.println("    No Cut-In:                   " + stats.CutInFails);

            for (int i = 0; i < RuinsMod.BIOME_NONE; i++)
            {
                if (stats.biomes[i] != 0)
                {
                    System.out.println(BiomeGenBase.getBiomeGenArray()[i].biomeName + ": " + stats.biomes[i] + " Biome building attempts");
                }
            }
            System.out.println("Any-Biome: " + stats.biomes[RuinsMod.BIOME_NONE] + " building attempts");
            
            System.out.println();
        }
    }

    private int getRandomAdjustment(Random random, int base, int minDistance)
    {
        return random.nextInt(8) - random.nextInt(8) + (random.nextInt(2) == 1 ? 0 - minDistance : minDistance);
    }

    private boolean willOverlap(RuinTemplate r, int x, int y, int z, int rotate)
    {
        final RuinData current = r.getRuinData(x, y, z, rotate);
        for (RuinData rd : registeredRuins)
        {
            if (rd.intersectsWith(current))
            {
                return true;
            }
        }
        return false;
    }
    
    private boolean checkMinDistance(RuinData ruinData)
    {
        // refuse Ruins spawning too close to each other        
        for (RuinData r : registeredRuins)
        {
            if (r.name.equals(ruinData.name))
            {
                if (r.getDistanceSqTo(ruinData) < ruinsHandler.templateInstancesMinDistance * ruinsHandler.templateInstancesMinDistance)
                {
                    return false;
                }
            }
            else
            {
                if (r.getDistanceSqTo(ruinData) < ruinsHandler.anyRuinsMinDistance * ruinsHandler.anyRuinsMinDistance)
                {
                    return false;
                }
            }
        }
        
        return true;
    }

    private int findSuitableY(World world, RuinTemplate r, int x, int z, boolean nether)
    {
        if (!nether)
        {
            for (int y = WORLD_MAX_HEIGHT - 1; y > 7; y--)
            {
                final Block b = world.getBlock(x, y, z);
                if (r.isIgnoredBlock(b, world, x, y, z))
                {
                    continue;
                }
                
                if (r.isAcceptableSurface(b))
                {
                    return y;
                }
                return -1;
            }
        }
        else
        {
            /*
             * The Nether has an entirely different topography so we'll use two
             * methods in a semi-random fashion (since we're not getting the
             * random here)
             */
            if ((x % 2 == 1) ^ (z % 2 == 1))
            {
                // from the top. Find the first air block from the ceiling
                for (int y = WORLD_MAX_HEIGHT - 1; y > -1; y--)
                {
                    final Block b = world.getBlock(x, y, z);
                    if (b == Blocks.air)
                    {
                        // now find the first non-air block from here
                        for (; y > -1; y--)
                        {
                            if (!r.isIgnoredBlock(world.getBlock(x, y, z), world, x, y, z))
                            {
                                if (r.isAcceptableSurface(b))
                                {
                                    return y;
                                }
                                return -1;
                            }
                        }
                    }
                }
            }
            else
            {
                // from the bottom. find the first air block from the floor
                for (int y = 0; y < WORLD_MAX_HEIGHT; y++)
                {
                    final Block b = world.getBlock(x, y, z);
                    if (!r.isIgnoredBlock(b, world, x, y, z))
                    {
                        if (r.isAcceptableSurface(b))
                        {
                            return y - 1;
                        }
                        return -1;
                    }
                }
            }
        }
        return -1;
    }
}