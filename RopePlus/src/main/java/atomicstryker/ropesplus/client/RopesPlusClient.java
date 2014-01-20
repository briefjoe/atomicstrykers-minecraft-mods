package atomicstryker.ropesplus.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.living.LivingFallEvent;

import org.lwjgl.input.Keyboard;

import atomicstryker.ropesplus.common.EntityFreeFormRope;
import atomicstryker.ropesplus.common.RopesPlusCore;
import atomicstryker.ropesplus.common.Settings_RopePlus;
import atomicstryker.ropesplus.common.arrows.EntityArrow303;
import atomicstryker.ropesplus.common.arrows.ItemArrow303;
import atomicstryker.ropesplus.common.network.ForgePacketWrapper;
import atomicstryker.ropesplus.common.network.PacketDispatcher;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.gameevent.TickEvent.Phase;

public class RopesPlusClient
{
    
    private Minecraft mc;
    private EntityArrow303 selectedArrow;
    private static int arrowCount;
    private int selectedSlot;
    private static EntityPlayer localPlayer;
    private GuiScreen prevScreen;
    private ItemStack prevItem;
    
    private int countDownToArrowCount;
    
    public static boolean grapplingHookOut;
    
    private static EntityFreeFormRope onZipLine;
    private static float lastZipLineLength;
    private static long timeNextZipUpdate;
    private static int zipTicker;
    private static boolean wasZiplining;
    
    public static boolean toolTipEnabled;
    
    private int guiStringX;
    private int guiStringY;
    
    private KeyBinding swapForward;
    private KeyBinding swapBackward;
    
    public RopesPlusClient()
    {
        mc = FMLClientHandler.instance().getClient();
        selectedArrow = null;
        arrowCount = -1;
        selectedSlot = 0;
        localPlayer = null;
        prevScreen = null;
        prevItem = null;
        countDownToArrowCount = 100;
        onZipLine = null;
        lastZipLineLength = 0;
        timeNextZipUpdate = 0;
        
        swapForward = new KeyBinding("SwapArrowsForward", Keyboard.KEY_COMMA, "key.categories.gameplay");
        swapBackward = new KeyBinding("SwapArrowsBackward", Keyboard.KEY_PERIOD, "key.categories.gameplay");
        ClientRegistry.registerKeyBinding(swapForward);
        ClientRegistry.registerKeyBinding(swapBackward);
        
        MinecraftForge.EVENT_BUS.register(this);
        
        Configuration c = Settings_RopePlus.config;
        c.load();
        guiStringX = c.get(Configuration.CATEGORY_GENERAL, "GUI String x coordinate, higher value means more to the right", 2).getInt();
        guiStringY = c.get(Configuration.CATEGORY_GENERAL, "GUI String y coordinate, higher value means lower", 10).getInt();
        c.save();
    }
    
    private void selectAnyArrow()
    {
        if(localPlayer == null)
        {
            selectedArrow = null;
            selectedSlot = 0;
            return;
        }
        
        findNextArrow(true);
        if(selectedArrow == null)
        {
            cycle(true);
        }
    }

    private void findNextArrow(boolean keepArrowType)
    {
        EntityArrow303 entityarrow303 = selectedArrow;
        findNextArrow(entityarrow303, 1, keepArrowType);
    }

    private void findPrevArrow()
    {
        EntityArrow303 entityarrow303 = selectedArrow;
        findNextArrow(entityarrow303, -1, false);
    }
    
    /**
     * Iterates forward or backward through the player inventory until a full cycle is done and
     * no other arrow could be found, or sets the selectedSlot to the newly found arrow and
     * propagates the update. Resumes from the other end of the inventory array when hitting it's boundaries.
     * 
     * @param previousarrow303 previously selected EntityArrow303
     * @param indexProgress how to iterate through the inventory
     * @param keepArrowType true if the new arrow type must match the old one, false if it must differ
     */
    private void findNextArrow(EntityArrow303 previousarrow303, int indexProgress, boolean keepArrowType)
    {
        int newSlot = keepArrowType ? selectedSlot : selectedSlot+indexProgress;
        
        int iterations = 0;
        while (iterations++ < localPlayer.inventory.mainInventory.length)
        {
            if (newSlot < 0)
            {
                newSlot = localPlayer.inventory.mainInventory.length-1;
            }
            else if (newSlot >= localPlayer.inventory.mainInventory.length)
            {
                newSlot = 0;
            }
            
            ItemStack itemstack = localPlayer.inventory.mainInventory[newSlot];
            if(itemstack == null)
            {
                newSlot += indexProgress;
                continue;
            }
            
            Item item = itemstack.getItem();
            
            // handle vanilla arrows
            if (item == Items.arrow)
            {
                EntityArrow303 itemarrow303 = new EntityArrow303(localPlayer.worldObj);
                if(previousarrow303 == null || previousarrow303.tip != itemarrow303.tip)
                {
                    selectedArrow = itemarrow303;
                    selectedSlot = newSlot;
                    sendPacketToUpdateArrowChoice();
                    return;
                }
                
                newSlot += indexProgress;
                continue;
            }
            
            // handle ropes+ arrows
            if (item == null || (!(item instanceof ItemArrow303)))
            {
                newSlot += indexProgress;
                continue;
            }
            
            ItemArrow303 itemarrow303 = (ItemArrow303)item;
            if(previousarrow303 == null || keepArrowType && itemarrow303.arrow == previousarrow303 || !keepArrowType && itemarrow303.arrow != previousarrow303)
            {
                selectedArrow = itemarrow303.arrow;
                selectedSlot = newSlot;
                sendPacketToUpdateArrowChoice();
                return;
            }
        }
        
        // failure to find anything!
        selectedArrow = null;
        selectedSlot = 0;
    }

    private int countArrows(EntityArrow303 entityarrow303)
    {
        int count = 0;
        InventoryPlayer inventoryplayer = localPlayer.inventory;
        ItemStack aitemstack[] = inventoryplayer.mainInventory;
        for(int k = 0; k < aitemstack.length; k++)
        {
            ItemStack itemstack = aitemstack[k];
            if(itemstack != null && itemstack.getItem() == entityarrow303.itemId)
            {
                count += itemstack.stackSize;
            }
        }

        return count;
    }

    private void sendPacketToUpdateArrowChoice()
    {
        arrowCount = -1;
        Object[] toSend = {selectedSlot};
        PacketDispatcher.sendPacketToServer(ForgePacketWrapper.createPacket("AS_Ropes", 1, toSend));
    }

    private void cycle(boolean directionForward)
    {
        EntityArrow303 previousarrow303 = selectedArrow;
        int i = selectedSlot;
        if(directionForward)
        {
            findNextArrow(false);
        }
        else
        {
            findPrevArrow();
        }
        
        ItemStack itemstack;
        Item item;
        
        if(selectedArrow == null
        && previousarrow303 != null
        && (itemstack = localPlayer.inventory.mainInventory[i]) != null
        && ((item = itemstack.getItem()) instanceof ItemArrow303)
        && ((ItemArrow303)item).arrow == previousarrow303)
        {
            selectedArrow = previousarrow303;
            selectedSlot = i;
            sendPacketToUpdateArrowChoice();
        }
    }
    
    /*========= ONTICK =============*/
    
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent tick)
    {
        if (tick.phase == Phase.END)
        {
            if(localPlayer != mc.thePlayer || prevScreen != mc.currentScreen)
            {
                localPlayer = mc.thePlayer;
                prevScreen = mc.currentScreen;
                selectAnyArrow();
            }
            
            if(mc.currentScreen == null)
            {
                ItemStack itemstack = mc.thePlayer.getCurrentEquippedItem();
                if (itemstack != prevItem)
                {
                    prevItem = itemstack;
                    selectAnyArrow();
                }
                
                if(toolTipEnabled
                && itemstack != null
                && (itemstack.getItem() == Items.bow || itemstack.getItem() == RopesPlusCore.bowRopesPlus))
                {
                    boolean hasArrows = selectedArrow != null;
                    String s = hasArrows ? selectedArrow.name : "No arrows";
                    if (hasArrows)
                    {
                        if (arrowCount == -1 || countDownToArrowCount-- < 0)
                        {
                            countDownToArrowCount = 100;
                            arrowCount = countArrows(selectedArrow);
                        }
                        s = s.concat("x"+arrowCount);
                    }
                    mc.fontRenderer.drawStringWithShadow(s, guiStringX, guiStringY, 0x2F96EB);
                    mc.fontRenderer.drawStringWithShadow("Swap arrows with "+Keyboard.getKeyName(swapForward.func_151463_i())+", "+Keyboard.getKeyName(swapBackward.func_151463_i()), guiStringX, guiStringY+10, 0xffffff);
                }
                
                if (swapForward.func_151470_d())
                {
                    cycle(false);
                }
                else if (swapBackward.func_151470_d())
                {
                    cycle(true);
                }
            }
            
            if (RopesPlusCore.proxy.getShouldHookShotPull() >= 0f)
            {
                if (mc.gameSettings.keyBindSneak.func_151470_d())
                {
                    RopesPlusCore.proxy.setShouldHookShotPull(RopesPlusCore.proxy.getShouldHookShotPull()+0.33f);
                }
            }
            
            if (onZipLine != null)
            {
                if (mc.gameSettings.keyBindUseItem.func_151470_d() && lastZipLineLength > 0.2)
                {
                    Object[] toSend = { onZipLine.func_145782_y(), lastZipLineLength };
                    PacketDispatcher.sendPacketToServer(ForgePacketWrapper.createPacket("AS_Ropes", 7, toSend));
                    onZipLine = null;
                }
                else if (System.currentTimeMillis() > timeNextZipUpdate)
                {
                    double startCoords[] = onZipLine.getCoordsAtRelativeLength(lastZipLineLength);
                    localPlayer.setPosition(startCoords[0], startCoords[1]-0.2D, startCoords[2]);
                    localPlayer.setVelocity(0, 0, 0);
                    localPlayer.fallDistance = 0f;
                    lastZipLineLength += 0.025;
                    timeNextZipUpdate = System.currentTimeMillis() + 50L;
                    
                    if (++zipTicker == 10)
                    {
                        zipTicker = 0;
                        Object[] toSend = { onZipLine.func_145782_y(), lastZipLineLength };
                        PacketDispatcher.sendPacketToServer(ForgePacketWrapper.createPacket("AS_Ropes", 7, toSend));
                    }
                    
                    if (lastZipLineLength > .9F)
                    {
                        onZipLine = null;
                    }
                }
            }
        }
    }
    
    public static void onAffixedToHookShotRope(int ropeEntID)
    {
        if (localPlayer != null && localPlayer.worldObj != null)
        {
            Entity ent = localPlayer.worldObj.getEntityByID(ropeEntID);
            if (ent != null && ent instanceof EntityFreeFormRope)
            {
                ((EntityFreeFormRope)ent).setShooter(localPlayer);
            }
        }
    }
    
    public static void onUsedZipLine(int ropeEntID)
    {
        if (localPlayer != null && localPlayer.worldObj != null)
        {
            Entity ent = localPlayer.worldObj.getEntityByID(ropeEntID);
            if (ent != null && ent instanceof EntityFreeFormRope)
            {
                onZipLine = (EntityFreeFormRope) ent;
                lastZipLineLength = 0;
                timeNextZipUpdate = System.currentTimeMillis();
                wasZiplining = true;
            }
        }
    }
    
    @SubscribeEvent
    public void onEntityLivingFall(LivingFallEvent event)
    {
        if (wasZiplining && event.entityLiving.equals(localPlayer))
        {
            wasZiplining = false;
            event.distance = 0f;      
        }
    }
    
}