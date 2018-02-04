import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/*
 * This is a client class. It connects with the server
 * and sends a file to server maintaining congestion control
 * and keeping a track of ACKS 
 */
public class client extends Thread {
	String serverAddress = "";
	String file = "";
	int time = 1000;
	boolean quiet;
	int port = 0;
	DataInputStream in;
	InetAddress IPAddress;
	DatagramSocket clientSocket;
	int expectedSeq = 1;
	Vector<DatagramPacket> sentPackets = new Vector<DatagramPacket>(4);
	Vector<Integer> ACK1 = new Vector<Integer>(4);
	int cwd = 10;
	int ssthresh = 20;
	int dupACK = 0;
	boolean congestavoidance = false;
	boolean ss = true;
	boolean dupACK3 = false;
	boolean fr = false;
	MessageDigest md5Digest;
	int allowedUNACK = 0;
	Vector<byte[]> sentBytes = new Vector<byte[]>();

	/*
	 * This constructor initializes all the values needed by the client to set
	 * up a connection
	 */
	public client(String file, int time, boolean quiet, String serverAddress, int port) throws UnknownHostException {
		this.serverAddress = serverAddress;
		this.file = file;
		this.time = time;
		this.quiet = quiet;
		this.port = port;

		IPAddress = InetAddress.getByName(serverAddress);
	}

	/*
	 * This function is the default function of the client thread. It
	 * establishes connection with the server, send chunks of data from a file
	 * and waits for ACK of all the packets sent Closes the connection, once all
	 * the ACKs have been received and the file has been sent.
	 */
	public void run() {

		// establish connection with the server
		handshake();
		int seq = 0;
		int allowedPackets = 20;
		InputStream is = null;
		BufferedReader bfReader = null;
		boolean ackReceived = false;
		boolean fileRead = false;
		boolean allACKED = false;
		int read=0;
		try {
			in = new DataInputStream(new FileInputStream(file));
			md5Digest = MessageDigest.getInstance("MD5");

			while (allACKED == false || fileRead == false) {
				if (fileRead == false) {
					for (int loop = 0; loop < Math.min((cwd - allowedUNACK), allowedPackets); loop++) {
						byte[] fileToServer = new byte[1431];
						// reading chunks of data
						if ((read=in.read(fileToServer, 0, 1431)) != -1) {
							byte readbytes[]=new byte[read];
							System.arraycopy(fileToServer, 0, readbytes, 0, read);
							
							sentBytes.add(readbytes);

							// adding headers
							byte[] withHeaders = addHeaders(seq, readbytes);
							// send the packet
							DatagramPacket sendPacket = new DatagramPacket(withHeaders, withHeaders.length, IPAddress,
									port);
							sentPackets.add(seq, sendPacket);
							ACK1.add(seq, 1);
							clientSocket.send(sendPacket);
							allowedUNACK++;
							if (quiet == true)
								System.out.println("Packet sent sequence number: " + seq);
							seq++;
						} else {
							fileRead = true;
							break;

						}
					}
				}
				// wait for specified time to receive ACK, after time elapses
				// resend that packet
				clientSocket.setSoTimeout(time);
				byte[] incomingData = new byte[8];
				DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
				try {
					clientSocket.receive(incomingPacket);
					incomingData = incomingPacket.getData();
					if (quiet == true) {
						System.out.println("Received ack:" + getInteger(incomingData, 0));
						System.out.println("Expected ack: " + expectedSeq);
					}
					if (getInteger(incomingData, 0) == expectedSeq) {
						if (quiet == true)
							System.out.println("Ack received for packet sequence number: " + (expectedSeq - 1));
						ackReceived = true;
						ACK1.set(expectedSeq - 1, 2);
						dupACK = 0;
						expectedSeq++;
						allowedPackets = getInteger(incomingData, 4);
						// slow-start
						if (ss == true && cwd < ssthresh)
							callSS();
						// congestion avoidance
						else if (ss == true && cwd >= ssthresh) {
							callCongestAvoidance();
							congestavoidance = true;
						} else if (congestavoidance == true)
							callCongestAvoidance();
						else if (fr == true) {
							cwd = ssthresh;
							callCongestAvoidance();
							fr = false;
							congestavoidance = true;

						}
						allowedUNACK--;
						// check if all have been acked
						if (checkAllACKED() == true)
							allACKED = true;
					}
					// check for duplicate packets
					else if (getInteger(incomingData, 0) == (expectedSeq - 1)) {
						dupACK++;
						if (fr == true)
							callFR();
						if (dupACK == 3) {
							ssthresh = cwd / 2;
							cwd = ssthresh + 3;
							fr = true;
							// sending the missing signal
							if (quiet == true)
								System.out.println("Duplicate ACK received.\n Retransmitting missing segment...");
							clientSocket.send(sentPackets.get(getInteger(incomingData, 0) - 1));
						}
					} else {
						clientSocket.send(sentPackets.get(expectedSeq - 1));
					}

				} catch (SocketTimeoutException e) {
					// if timed out, re-send.
					if (quiet == true) {
						System.out.println("Timed out. Sending packet with sequence number " + seq + ", again");
						System.out.println("Slow Start initiated.");
					}
					clientSocket.send(sentPackets.get(expectedSeq - 1));
					ssthresh = cwd / 2;
					cwd = 1;
					dupACK = 0;
					ss = true;
					fr = false;
					congestavoidance = false;

					continue;
				}

				ackReceived = false;
			}
			// close the connection
			closeConnection();
		} catch (FileNotFoundException e) {

			e.printStackTrace();
		} catch (IOException e) {

			e.printStackTrace();
		} catch (NoSuchAlgorithmException e1) {
			e1.printStackTrace();
		}
	}

	/*
	 * This function increments the cwd by 1
	 */
	private void callFR() {
		cwd += 1;
		if (quiet == true)
			System.out.println("Congestion control status: In FastRecovery.");

	}

	/*
	 * This function increments the cwd by (cwd+(1/cwd))
	 */
	private void callCongestAvoidance() {
		cwd = cwd + (1 / cwd);
		dupACK = 0;
		if (quiet == true)
			System.out.println("Congestion control status: In Congestion Avoidance.");
	}

	/*
	 * This function increments the cwd by 1 set the dupAck to 0
	 */
	private void callSS() {
		cwd += 1;
		dupACK = 0;
		if (quiet == true)
			System.out.println("Congestion control status: In Slow Start.");

	}

	/*
	 * This function initiates the connection closing request The client sends
	 * the FIN bit and wait for the ACK and FIN bit from the server Wait for
	 * 1000ms to receive any resend request from the server eventually end the
	 * connection
	 */
	private void closeConnection() {
		try {
			int countmd = 0;
			int size=0;
			for(int i=0;i<sentBytes.size();i++)
			{
				size+=sentBytes.get(i).length;
			}
			byte[] md5value = new byte[size];
			for (int i = 0; i < sentBytes.size(); i++)
				for (int j = 0; j < sentBytes.get(i).length; j++) {
					md5value[countmd] = sentBytes.get(i)[j];
					countmd++;
				}
			md5Digest.update(md5value, 0, md5value.length);

			System.out.println("MD5: " + new BigInteger(1, md5Digest.digest()).toString(16));
			boolean received = false;
			int count = 0;
			if (quiet == true)
				System.out.println("Closing Connection. Sending FIN...");
			byte[] sendData = new byte[1];
			sendData[0] = (byte) 1;
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
			clientSocket.send(sendPacket);
			clientSocket.setSoTimeout(time);
			while (received == false) {
				try {
					byte[] incomingData = new byte[4];
					DatagramPacket receivePacket = new DatagramPacket(incomingData, incomingData.length);
					clientSocket.receive(receivePacket);
					incomingData = receivePacket.getData();

					if (getInteger(incomingData, 0) == 16 || getInteger(incomingData, 0) == 1)
						count++;
					if (count == 1)
						System.out.println("Received ACK");
					else
						System.out.println("Received FIN");

					if (count == 2) {
						received = true;
						byte[] sendData1 = new byte[4];
						getByte(16, sendData1, 0);
						DatagramPacket sendPacket1 = new DatagramPacket(sendData1, sendData1.length, IPAddress, port);
						clientSocket.send(sendPacket1);

						boolean lastACK = false;
						clientSocket.setSoTimeout(1000);
						while (lastACK == false) {
							try {
								byte[] ACKlost = new byte[4];
								DatagramPacket ACKlostPacket = new DatagramPacket(ACKlost, ACKlost.length);
								clientSocket.receive(ACKlostPacket);
								clientSocket.send(sendPacket1);
								lastACK = true;
							} catch (SocketTimeoutException e) {
								System.out.println("Waited for 1000\nClosing Connection");
								in.close();
								clientSocket.close();
								lastACK = true;
								System.out.println("Connection closed.");
								
							}

						}

					}

				} catch (SocketTimeoutException e) {
					// if timed out, re-send.
					System.out.println("Timed out. Sending packet FIN, again");
					clientSocket.send(sendPacket);
					continue;
				}
			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/*
	 * This function checks if all the packets have been acked
	 */
	private boolean checkAllACKED() {
		for (int check = 0; check < ACK1.size(); check++) {
			if (ACK1.get(check) == 1)
				return false;
		}
		return true;

	}

	/*
	 * This function initiates the connection establishment process It sends SYN
	 * bit to the server and waits for ACK
	 */
	private void handshake() {

		try {
			clientSocket = new DatagramSocket();
			byte[] handshakeSyn = new byte[5];
			byte[] incomingData = new byte[5];
			byte syn = (byte) 128;
			boolean ackReceived = false;
			handshakeSyn[0] = syn;
			getByte(0, handshakeSyn, 1);
			DatagramPacket sendPacket = new DatagramPacket(handshakeSyn, handshakeSyn.length, IPAddress, port);
			clientSocket.send(sendPacket);

			// wait for the ack
			clientSocket.setSoTimeout(time);
			while (ackReceived == false) {
				DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
				try {
					clientSocket.receive(incomingPacket);
					incomingData = incomingPacket.getData();
					if (((int) incomingData[0] == -128) && (getInteger(incomingData, 1) == 1)) {
						System.out.println("Connection Established");
						ackReceived = true;
					}

				} catch (SocketTimeoutException e) {
					// if timed out, re-send.
					System.out.println("Timed out. Reconnecting...");
					clientSocket.send(sendPacket);
					continue;
				}
			}

		} catch (SocketException e) {

			e.printStackTrace();
		} catch (IOException e) {

			e.printStackTrace();
		}

	}

	/*
	 * This function is used to conver integers to byte[4]
	 */
	public void getByte(int num, byte[] handshakeSyn1, int start) {

		int count = 3;
		for (int i = start; i < start + 4; i++) {

			handshakeSyn1[i] = (byte) (num >>> (count * 8));
			count--;
		}

	}

	/*
	 * This function is used to revert byte[4] to integer
	 */
	public int getInteger(byte[] bytes, int start) {
		return ((bytes[start] & 0xFF) << 24) | ((bytes[start + 1] & 0xFF) << 16) | ((bytes[start + 2] & 0xFF) << 8)
				| (bytes[start + 3] & 0xFF);
	}

	/*
	 * This function adds checksum, sequence number to the payload
	 */
	public byte[] addHeaders(int seq, byte[] fileToServer) {

		// calculating the checksum
		// md5Digest.update(fileToServer, 0, fileToServer.length);

		byte[] withHeaders = new byte[fileToServer.length+9];

		// syn byte
		withHeaders[0] = (byte) 0;

		// sequence number
		getByte(seq, withHeaders, 1);

		// calculate checksum
		getByte((int) calculateChecksum(fileToServer), withHeaders, 5);

		System.arraycopy(fileToServer, 0, withHeaders, 9, fileToServer.length);
		return withHeaders;

	}

	/*
	 * This function calculates the IP checksum referred from stack overflow
	 */
	private long calculateChecksum(byte[] fileToServer) {
		int bufLength = fileToServer.length;
		int i = 0;
		long total = 0;
		while (bufLength > 0) {
			total += (fileToServer[i++] & 0xff) << 8;
			if ((--bufLength) == 0)
				break;
			total += (fileToServer[i++] & 0xff);
			--bufLength;
		}

		return (~((total & 0xFFFF) + (total >> 16))) & 0xFFFF;
	}

}
