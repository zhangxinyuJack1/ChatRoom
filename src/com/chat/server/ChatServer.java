package com.chat.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Vector;

public class ChatServer implements Runnable{

	//选择器
    private Selector selector;  
    
    //注册ServerSocketChannel后的选择键
    private SelectionKey serverKey;  
    
    //标识是否运行  
    private boolean isRun;
    
    //当前聊天室中的用户名称列表  
    private Vector<String> usernames;  
    
    //时间格式化器  
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");  
  
    /** 
     * 构造函数 
     * @param port 服务端监控的端口号 
     */  
    public ChatServer(int port) {  
        isRun = true;  
        usernames = new Vector<String>();  
        init(port);  
    }  

    private void init(int port){
    	try {
    		//开启选择器
    		selector = Selector.open();
    		//获得服务器套接字
    		ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
    		//绑定端口号
    		//serverChannel.socket().bind(new InetSocketAddress(port));
    		serverSocketChannel.bind(new InetSocketAddress(port));// jdk1.7之后可以直接这样写
    		//设置为非阻塞
    		serverSocketChannel.configureBlocking(false);
    		//将serverSocketChannel注册到选择器, 指定其行为 "等待接受连接OP_ACCEPT"
    		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    		printInfo("waiting connect....");  
         } catch (IOException e) {  
             e.printStackTrace();  
         }  
    }
    
	@Override
	public void run() {
		try {
			//轮询选择器的 选择键 TODO ????
			while (isRun) {
				//选择一组已经准备进行IO操作通道的key,等于1时候表示有这样的key
//				int n = selector.select(); 这个方法是阻塞的 会一直等到有通道兴趣发生
//				int n = selector.select(10000);
				int n = selector.selectNow();
				if(n > 0) {
					//从选择器上 获取已选择key的集合
					Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
					while (iter.hasNext()) {
						SelectionKey key = iter.next();
						//若这个key的通道兴趣是 等待接受连接OP_ACCEPT
						if (key.isAcceptable()) {
							//remove掉，否则之后新连接会被阻塞
							iter.remove();
							//活动key对应的通道
							ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
							//接受新的连接返回和客户端对等的套接字通道
							SocketChannel channel = serverSocketChannel.accept();
							if (channel == null) {
								continue;
							}
							channel.configureBlocking(false);
							//将这个套接字通道注册到选择器，指定其行为为"读"
							channel.register(selector, SelectionKey.OP_READ);
						}
						if (key.isReadable()) {
							readMsg(key);
						}
						if (key.isWritable()) {
							writeMsg(key);
						}
					}
				}
            }  
        } catch (IOException e) {
        	e.printStackTrace();
        }
	}
	
	private void readMsg(SelectionKey key) throws IOException{
		SocketChannel channel = (SocketChannel) key.channel();
		//创建一个大小为1024k的缓存区  
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        StringBuffer sb = new StringBuffer();
        //将通道的数据读到缓存区
        int count = channel.read(buffer);
        if (count > 0) {
        	//翻转缓存区(将缓存区由写进数据模式变成读出数据模式)
        	buffer.flip();
        	//将缓存区的数据转成String
        	sb.append(new String(buffer.array(), 0, count));
        }
        String str = sb.toString();
        //若消息中有"open_",表示客户端准备进入聊天界面
        //"open_zhangxinyu",表示用户zhangxinyu请求打开聊天窗体
        //更新用户名列表，然后将用户名数据写给每一个已连接的客户端
        if (str.indexOf("open_") != -1) {
			String username = str.substring(5);
			usernames.add(username);
			printInfo(username + " online"); 
			Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
			while (iter.hasNext()) {
				SelectionKey selectionKey = iter.next();
				//若不是服务器套接字通道的key，则将数据设置到此key中
				if (selectionKey != serverKey) {
					//更新此key的感兴趣动作
					selectionKey.attach(usernames);
					selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
				}
			}
		} else if (str.indexOf("exit_") != -1) {
			String username = str.substring(5);
			usernames.add(username);
			key.attach("close");
			key.interestOps(SelectionKey.OP_WRITE);
			//获取选择器上的已选择key 并更新
			Iterator<SelectionKey> iter = key.selector().selectedKeys().iterator();
			while (iter.hasNext()) {
				SelectionKey selectionKey = iter.next();
				selectionKey.attach(usernames);
				selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
			}
			printInfo(username + " offline");
		} else {// 读取客户端聊天消息
			String username = str.substring(0, str.indexOf("^"));
			String msg = str.substring(str.indexOf("^") + 1);
			printInfo("("+username+")说：" + msg);
			String dateTime = sdf.format(new Date());
			String smsg = username + " " + dateTime + "\n  " + msg + "\n";
			Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
            while (iter.hasNext()) {
            	SelectionKey selKey = iter.next();
            	if (selKey != serverKey) {
					selKey.attach(smsg);  
					selKey.interestOps(selKey.interestOps() | SelectionKey.OP_WRITE);
            	}
            }
        }
	}
	
	private void writeMsg(SelectionKey key) throws IOException{
		SocketChannel channel = (SocketChannel) key.channel();  
        Object obj = key.attachment();  
        //这里必要要将key的附加数据设置为空，否则会有问题  
        key.attach("");  
        //附加值为"close"，则取消此key，并关闭对应通道  
        if (obj.toString().equals("close")) {  
            key.cancel();  
            channel.socket().close();  
            channel.close();  
            return;  
        }else {  
            //将数据写到通道  
            channel.write(ByteBuffer.wrap(obj.toString().getBytes()));  
        }  
        //重设此key兴趣  
        key.interestOps(SelectionKey.OP_READ);  
	}
	
	private void printInfo(String str) {  
        System.out.println("[" + sdf.format(new Date()) + "] -> " + str);  
    }  
	
	public static void main(String[] args) {
		ChatServer chatServer = new ChatServer(19999);
		new Thread(chatServer).start();//线程一直监听
	}
}
