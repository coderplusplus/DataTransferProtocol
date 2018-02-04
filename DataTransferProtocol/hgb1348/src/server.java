import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/*
 * The server class connects with the client and receive the file from client
 * sends Ack for each packet received
 */
public class server extends Thread {
	boolean quiet;
	int port = 0;
	DatagramSocket server;
	InetAddress clientAddress;
	int clientPort;
	int receivedSequence;
	boolean dataCorrupted;
	int startWindow = 0;
	Vector<byte[]> receivedPackets = new Vector<byte[]>(4);
	int sizeOfTheBuffer = 0;
	Vector<byte[]> file = new Vector<byte[]>();
	MessageDigest md5Digest;
	Vector<byte[]> MD = new Vector<byte[]>();

	public server(boolean quiet, int port) {
		this.quiet = quiet;
		this.port = port;
	}

	/*
	 * This function is the default function of the server thread it establishes
	 * connection with the client receives data from the client and send Acks
	 * for the same and ends connection once all the data has been received
	 */
	public void run() {
		int seqCount = 0;
		try {
			System.out.println("Server is listening on port: " + port);
			// establish connection
			handshake();
			// to calculate M5
			md5Digest = MessageDigest.getInstance("MD5");

			while (true) {

				byte[] incomingData1 = new byte[1440];
				DatagramPacket incomingPacket = new DatagramPacket(incomingData1, incomingData1.length);
				server.receive(incomingPacket);
				byte[] incomingData = new byte[incomingPacket.getLength()];
				incomingData1 = incomingPacket.getData();
				for (int i = 0; i < incomingPacket.getLength(); i++) {
					incomingData[i] = incomingData1[i];
				}
				// if connection close, its 1
				if ((int) incomingData[0] != 1) {
					// read the checksum and seq number
					readHeaders(incomingData);
					// if not the quiet, display
					if (quiet == true)
						System.out
								.println("Server: Sequence expected new: " + seqCount + "\nServer: Sequence received: "
										+ receivedSequence + "\nServer: Is data corrupted: " + dataCorrupted);

					int fromBuffer = 0;
					// check if the packet received is what was expected or is
					// presented in the buffer
					if ((receivedSequence == seqCount) || ((fromBuffer = presentInBuffer(seqCount)) != -1)) {
						// if its the expected sequence
						if (receivedSequence == seqCount) {
							// if data is not corrupted
							if (dataCorrupted == false) {
								if (quiet == true)
									System.out.println("Sequence Number as expected. \nChecksum matched");
								seqCount++;
								byte[] payload = new byte[incomingData.length - 9];
								for (int i = 9; i < incomingData.length; i++)
									payload[i - 9] = incomingData[i];
								MD.addElement(payload);
								sendACK(seqCount, 20 - sizeOfTheBuffer);
							}
							// if data is corrupted,send ack
							else {
								if (quiet == true)
									System.out.println("Checksum not matched.");
								sendACK(seqCount, 20 - sizeOfTheBuffer);
							}
						}
						// if found in the buffer
						else {
							byte[] payload = new byte[receivedPackets.get(fromBuffer).length - 9];
							for (int i = 9; i < receivedPackets.get(fromBuffer).length; i++) {
								payload[i - 9] = receivedPackets.get(fromBuffer)[i];
							}
							MD.addElement(payload);

							if (quiet == true)
								System.out.println("Packet from the buffer");
							receivedPackets.remove(fromBuffer);
							sizeOfTheBuffer--;
							seqCount++;
							sendACK(seqCount, 20 - sizeOfTheBuffer);
						}

					} else {

						// add it to the buffer, if data is not corrupted
						if ((dataCorrupted == false) && (receivedSequence > seqCount)
								&& (sizeOfTheBuffer <= 20 && (presentInBuffer(receivedSequence) == -1))) {
							receivedPackets.add(incomingData);
							sizeOfTheBuffer++;
							if (quiet == true)
								System.out.println("Out of order packet received. Stored in the buffer");
						}

						// send ack for seq expected
						sendACK(seqCount, 20 - sizeOfTheBuffer);

					}

				} else {
					closeConnection(incomingData);
					break;
				}
			}

		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}

	}

	/*
	 * This function initiates the connection closing request The server
	 * recevies the FIN bit and send ACK and FIN bit to the client Wait for
	 * 3000ms to receive the ACK fromt he client, else resends the FIN bit
	 * eventually end the connection
	 */
	private void closeConnection(byte[] close) throws IOException {
		int count = 0;
		int size = 0;
		for (int i = 0; i < MD.size(); i++) {
			size += MD.get(i).length;
		}
		byte[] md5value = new byte[size];

		for (int i = 0; i < MD.size(); i++)
			for (int j = 0; j < MD.get(i).length; j++) {
				md5value[count] = MD.get(i)[j];
				count++;
			}
		md5Digest.update(md5value, 0, md5value.length);
		System.out.println("MD5: " + new BigInteger(1, md5Digest.digest()).toString(16));
		DatagramPacket sendPacket = null;
		if ((int) close[0] == 1) {
			for (int i = 0; i < 2; i++) {
				byte[] sendData = new byte[4];
				if (i == 0) {
					// sending ack
					getByte(16, sendData, 0);
					System.out.println("Sending ACK");
				} else {
					// sending fin
					getByte(1, sendData, 0);
					System.out.println("Sending FIN");
				}
				sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);
				server.send(sendPacket);
			}

			boolean lastACK = false;
			int closingCount = 1;
			server.setSoTimeout(1000);
			while (lastACK == false && closingCount <= 3) {
				try {
					byte[] incomingData = new byte[4];
					DatagramPacket receivePacket = new DatagramPacket(incomingData, incomingData.length);
					server.receive(receivePacket);
					incomingData = receivePacket.getData();
					if (getInteger(incomingData, 0) == 16) {
						System.out.println("Closing connection..");
						server.close();
						lastACK = true;
						System.out.println("Connection closed");

					}
				} catch (SocketTimeoutException e) {
					System.out.println("ACK not received. Resending..");
					server.send(sendPacket);
					closingCount++;
					continue;
				}
			}
			if (lastACK == false) {
				System.out.println("Closing connection..");
				server.close();
				System.out.println("Connection closed");
			}
		}

	}

	/*
	 * This method checks if the packet is already found in the buffer returns
	 * -1 if not found returns the index of the packet if found
	 */
	private int presentInBuffer(int seqCount) {

		for (int check = 0; check < sizeOfTheBuffer; check++) {
			if (getInteger(receivedPackets.get(check), 1) == seqCount)
				return check;
		}

		return -1;
	}

	/*
	 * This function sends the ack to the server along with the receive window
	 */
	private void sendACK(int seqCount, int buffer) {
		try {
			if (quiet == true)
				System.out.println("Sending ack: " + seqCount);
			byte acknowledgement[] = new byte[8];
			getByte(seqCount, acknowledgement, 0);
			getByte(buffer, acknowledgement, 4);
			DatagramPacket sendPacket = new DatagramPacket(acknowledgement, acknowledgement.length, clientAddress,
					clientPort);

			server.send(sendPacket);
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/*
	 * This function initiates the connection establishment process It sends SYN
	 * bit to the client in response the the SYN bit from the client
	 */
	private void handshake() {
		try {
			server = new DatagramSocket(port);
			byte[] incomingData = new byte[5];
			byte[] sendData = new byte[5];

			DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
			server.receive(incomingPacket);
			incomingData = incomingPacket.getData();
			if ((int) incomingData[0] == -128) {
				clientPort = incomingPacket.getPort();
				clientAddress = incomingPacket.getAddress();
				sendData[0] = (byte) 128;
				getByte(getInteger(incomingData, 1) + 1, sendData, 1);
				DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);
				server.send(sendPacket);

			}
			System.out.println("Connection Established!");

		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
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
	 * This function is used to conver integers to byte[4]
	 */
	public void getByte(int num, byte[] array, int start) {
		int count = 3;
		for (int i = start; i < start + 4; i++) {
			array[i] = (byte) (num >>> (count * 8));
			count--;
		}

	}

	/*
	 * This function reads the seq number and the checksum from the packet
	 * received
	 */
	public void readHeaders(byte[] withHeaders) throws NoSuchAlgorithmException {
		// reading the sequence number
		receivedSequence = getInteger(withHeaders, 1);

		// reading the checksum
		if (quiet == true) {
			System.out.println("Checksum received:" + getInteger(withHeaders, 5));

			System.out.println("Checksum Calculated: " + (int) calculateChecksum(withHeaders));
		}

		if (getInteger(withHeaders, 5) == (int) calculateChecksum(withHeaders))
			dataCorrupted = false;
		else
			dataCorrupted = true;

	}

	/*
	 * This function calculates the IP checksum referred from stack overflow
	 */
	private long calculateChecksum(byte[] fileToServer) {
		byte[] payload = new byte[fileToServer.length - 9];

		for (int i = 0; i < payload.length; i++)
			payload[i] = fileToServer[i + 9];

		int bufLength = payload.length;
		int i = 0;
		long total = 0;
		while (bufLength > 0) {
			total += (payload[i++] & 0xff) << 8;
			if ((--bufLength) == 0)
				break;
			total += (payload[i++] & 0xff);
			--bufLength;
		}

		return (~((total & 0xFFFF) + (total >> 16))) & 0xFFFF;
	}

}
