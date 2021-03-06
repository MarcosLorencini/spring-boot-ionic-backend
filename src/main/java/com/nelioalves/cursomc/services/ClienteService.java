package com.nelioalves.cursomc.services;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.nelioalves.cursomc.domain.Cidade;
import com.nelioalves.cursomc.domain.Cliente;
import com.nelioalves.cursomc.domain.Endereco;
import com.nelioalves.cursomc.domain.enums.Perfil;
import com.nelioalves.cursomc.domain.enums.TipoCliente;
import com.nelioalves.cursomc.dto.ClienteDTO;
import com.nelioalves.cursomc.dto.ClienteNewDTO;
import com.nelioalves.cursomc.repositories.CidadeRepository;
import com.nelioalves.cursomc.repositories.ClienteRepository;
import com.nelioalves.cursomc.repositories.EnderecoRepository;
import com.nelioalves.cursomc.security.UserSS;
import com.nelioalves.cursomc.service.exception.AuthorizationException;
import com.nelioalves.cursomc.service.exception.DataIntegrityException;
import com.nelioalves.cursomc.service.exception.ObjectNotFoundException;

@Service
public class ClienteService {
	
	//na criacao da senha a mesma é criptografada
	@Autowired
	private BCryptPasswordEncoder pe;
	
	@Autowired
	private ClienteRepository repo;
	
	@Autowired
	private CidadeRepository cidadeRepository;
	
	@Autowired
	private S3Service s3Service;
	
	@Autowired
	private EnderecoRepository enderecoRepository;
	
	@Autowired
	private ImageService imageService;
	
	//importa o prexifo cp do application.properties para definir o prefix do cliente
	@Value("${img.prefix.client.profile}")
	private String prefix;
	
	
	//importa o prexifo cp do application.properties para definir o tamanho da imagem
	@Value("${img.profile.size}")
	private Integer size;
	
	
	
	public Cliente find(Integer id) {
		
		//pega o cliente logado
		UserSS user = UserService.authenticated();
		if (user==null || !user.hasRole(Perfil.ADMIN) && !id.equals(user.getId())) {
			throw new AuthorizationException("Acesso Negado");
		}
		
		Optional<Cliente> obj = repo.findById(id);
		return obj.orElseThrow(() -> new ObjectNotFoundException("Objeto não encontrado! Id: "+id+" Tipo: "+Cliente.class.getName()));
	}
	//o telefone é um conjunto dentro do cliente, porém o endereço tem que salvar
	public Cliente insert(Cliente obj) {
		obj.setId(null);//o metodo save identidica se o id vier vazio é um objeto novo o id no banco será null, se o id vier preenchido é um update. 
		obj = repo.save(obj);
		enderecoRepository.saveAll(obj.getEndereco());//no metodo from tem a associaçao 
		return obj;
	}
	
	
    //o metodo save serve para inserir e tbm atualizar o obj a única diferença é o metodo obj.setId(null);
	public Cliente update(Cliente obj) {
		//deve buscar o cliente no banco de dados para conseguir atualizar todas as informações da classe cliente, se atualizar somente
		//os campos do clienteDTO os outros campos na classe Cliente irão ficar vazio
		Cliente newObj = find(obj.getId());//busca o id no banco e caso não exista lanca uma exception
		//atualiza os dados que buscou no banco, novo objeto(newObj) com base no objeto que veio como argumento(obj)
		upDateDate(newObj, obj);
		return repo.save(newObj);
	}
		
		

	public void delete(Integer id) {
		find(id);//caso o id não exista dispara uma exception
		try {
			repo.deleteById(id);
		}catch(DataIntegrityViolationException e) {
			//tenho que lancar uma execpeiton minha quando lancar uma exception DataIntegrityViolationException
			throw new DataIntegrityException("Não é possivel excluir porque há pedidos relacionadas");
			
		}
	}
	
	public List<Cliente> findAll(){
		return repo.findAll();
	}
	
	//endpoint que busca o cliente por email
	//para que possa buscar os dados do cliente por email
	public Cliente findByEmail(String email) {
		//usuario que está autenticado
		UserSS user = UserService.authenticated();
		if(user == null || !user.hasRole(Perfil.ADMIN) && !email.equals(user.getUsername())) {
			throw new AuthorizationException("Acesso negado");
		}
		//chama o findbyemail do repositorio
		Cliente obj = repo.findByEmail(email);
		if(obj == null) {
			throw new ObjectNotFoundException("Objeto não encontrado! Id: "+user.getId()+", Tipo: "+Cliente.class.getName());
		}
		return obj;
		
	}
	
	
	
	//paginação dos clientes
	//page, tamanhodapagina, direcao e camposparaordenar
	public Page<Cliente> findPage(Integer page, Integer linesPerPage, String orderBy, String direction){
		PageRequest pageRequest = new PageRequest(page, linesPerPage, Direction.valueOf(direction), orderBy);
		return repo.findAll(pageRequest);
	}
	
	//instancia um cliente a partir de um dto
	public Cliente fromDto(ClienteDTO objDto) {
		return new Cliente(objDto.getId(), objDto.getNome(), objDto.getEmail(), null, null, null);
	}
	
	
	private void upDateDate(Cliente newObj, Cliente obj) {
		newObj.setNome(obj.getNome());
		newObj.setEmail(obj.getEmail());
		
	}
	
	//TipoCliente.toEnum(objDto.getTipo()) convertendo para o Enum TipoCliente 
	//instancia um cliente a partir de um dto
	//o cliente tem que ter pelo menos 1 telefone 1 endereco. O endereco tem que ter pelo menos uma ciadade
	public Cliente fromDto(ClienteNewDTO objDto) {
		Cliente cli = new Cliente(null, objDto.getNome(), objDto.getEmail(), objDto.getCpfOuCnpj(), TipoCliente.toEnum(objDto.getTipo()), pe.encode(objDto.getSenha()));
		//pega a cidade do banco de dados
		Cidade cid = cidadeRepository.findById(objDto.getCidadeId()).get();
		 //O endereco tem que ter pelo menos um cliente e uma cidade
		Endereco end = new Endereco(null, objDto.getLogradouro(), objDto.getNumero(), objDto.getComplemento(), objDto.getBairro(), objDto.getCep(), cli, cid);
		//o cliente tem que ter pelo menos 1 endereco. tem que salvar o endereço tbm
		cli.getEndereco().add(end);
		//o cliente tem que ter pelo menos 1 telefone
		cli.getTelefones().add(objDto.getTelefone1());
		if(objDto.getTelefone2()!=null) {
			cli.getTelefones().add(objDto.getTelefone2());
		}
		if(objDto.getTelefone3()!=null) {
			cli.getTelefones().add(objDto.getTelefone3());
		}
		return cli;
	}
	
	//fazer o upload da imagem
	public URI uploadProfilePicture(MultipartFile multipartFile) {
		
		//pega o cliente logado para salvar a url da imagem no cliente
		UserSS user = UserService.authenticated();
		if(user == null) {
			throw new AuthorizationException("Acesso negado");
		}
		
		//extrair o jpg a partir do arquivo que foi enviado na requisicao
		BufferedImage jpgImage = imageService.getJpgImageFromFile(multipartFile);
		
		//recorta a imagem de forma que fique quadrada
		jpgImage = imageService.cropSquare(jpgImage);
		
		//faz ficar no tamanho que foi definido no application.properties img.profile.size=200
		jpgImage = imageService.resize(jpgImage, size);
		
		//montar o nome do arquivo personalizado a partir do cliente que está logado
		String fileName = prefix + user.getId() + ".jpg";
		
		return s3Service.uploadFile(imageService.getInputStream(jpgImage, "jpg"), fileName, "image");
		
		
				
	}

}
